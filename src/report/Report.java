package report;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPException;

import org.xml.sax.SAXException;

import acknowledge.Ack;
import amend_manager.ReportXmlBuilder;
import app_config.AppPaths;
import app_config.DebugConfig;
import app_config.PropertiesReader;
import dataset.Dataset;
import dataset.DatasetList;
import dataset.DatasetStatus;
import dataset.IDataset;
import message.MessageConfigBuilder;
import message.MessageResponse;
import message.SendMessageException;
import message_creator.OperationType;
import table_database.TableDao;
import table_skeleton.TableRow;
import table_skeleton.TableVersion;
import webservice.GetAck;
import webservice.GetDatasetList;
import webservice.MySOAPException;
import webservice.SendMessage;
import xlsx_reader.TableSchema;
import xlsx_reader.TableSchemaList;

public abstract class Report extends TableRow implements EFSAReport, IDataset {

	public Report(TableRow row) {
		super(row);
	}
	
	public Report(TableSchema schema) {
		super(schema);
	}
	
	public Report() {
		super();
	}
	
	@Override
	public boolean isBaselineVersion() {
		return TableVersion.isFirstVersion(this.getVersion());
	}
	
	/**
	 * Check if the a report with the chosen senderDatasetId 
	 * is already present in the database
	 * @param senderDatasetId
	 * @return
	 */
	public static boolean isLocallyPresent(String senderDatasetId) {
		
		if (senderDatasetId == null)
			return false;
		
		// check if the report is already in the db
		TableDao dao = new TableDao(TableSchemaList.getByName(AppPaths.REPORT_SHEET));
		
		for (TableRow row : dao.getAll()) {

			String otherSenderDatasetId = row.getCode(AppPaths.REPORT_SENDER_ID);
			
			// if same sender dataset id then return true
			if (otherSenderDatasetId != null 
					&& otherSenderDatasetId.equals(senderDatasetId))
				return true;
		}
		
		return false;
	}
	
	/**
	 * Send the report contained in the file
	 * and update the report status accordingly.
	 * NOTE only for expert users. Otherwise use
	 * {@link #exportAndSend()} to send the report
	 * with an atomic operation.
	 * @param file
	 * @throws SOAPException
	 * @throws SendMessageException
	 */
	public void send(File file, OperationType opType) throws SOAPException, SendMessageException {

		// send the report and get the response to the message
		SendMessage req = new SendMessage(file);
		MessageResponse response = req.send();

		// if correct response then save the message id
		// into the report
		if (response.isCorrect()) {

			// save the message id
			this.setMessageId(response.getMessageId());
			
			// update report status based on the request operation type
			DatasetStatus newStatus;
			switch(opType) {
			case INSERT:
			case REPLACE:
				newStatus = DatasetStatus.UPLOADED;
				break;
			case REJECT:
				newStatus = DatasetStatus.REJECTION_SENT;
				break;
			case SUBMIT:
				newStatus = DatasetStatus.SUBMISSION_SENT;
				break;
			default:
				newStatus = null;
				break;
			}
			
			if (newStatus != null) {
				this.setStatus(newStatus);
				this.update();
			}
		}
		else {

			// set upload failed status if message is not valid
			this.setStatus(DatasetStatus.UPLOAD_FAILED);
			this.update();

			throw new SendMessageException(response);
		}
	}
	
	/**
	 * Given a report and its state, get the operation
	 * that is correct for sending it to the dcf.
	 * For example, if the report was never sent then the operation
	 * will be {@link OperationType#INSERT}.
	 * @param report
	 * @return
	 * @throws ReportException 
	 * @throws MySOAPException 
	 */
	public ReportSendOperation getSendOperation() throws MySOAPException, ReportException {
		
		OperationType opType = OperationType.NOT_SUPPORTED;
		
		Dataset dataset = this.getDataset();
		
		// if no dataset is present => we do an insert
		if (dataset == null)
			return new ReportSendOperation(null, OperationType.INSERT);
		
		// otherwise we check the dataset status
		DatasetStatus status = dataset.getStatus();
		
		switch (status) {
		case REJECTED_EDITABLE:
		case VALID:
		case VALID_WITH_WARNINGS:
			opType = OperationType.REPLACE;
			break;
		case DELETED:
			opType = OperationType.INSERT;
			break;
		default:
			opType = OperationType.NOT_SUPPORTED;
			throw new ReportException("No send operation for status " 
					+ status + " is supported");
		}
		
		ReportSendOperation operation = new ReportSendOperation(dataset, opType);
		
		return operation;
	}
	
	/**
	 * Get all the datasets in the DCF that have the as senderDatasetId
	 * the one given in input.
	 * @param report
	 * @return
	 * @throws SOAPException
	 * @throws ReportException 
	 */
	public DatasetList<Dataset> getDatasets() throws MySOAPException, ReportException {
		
		// check if the Report is in the DCF
		GetDatasetList request = new GetDatasetList(PropertiesReader
				.getDataCollectionCode(this.getYear()));

		String senderDatasetId = this.getSenderId();
		
		// add also the version to match correctly the dataset sender id
		// but if we have the baseline use just the sender id
		if (senderDatasetId != null && !this.getVersion().isEmpty() 
				&& !TableVersion.isFirstVersion(this.getVersion())) {
			senderDatasetId = TableVersion.mergeNameAndVersion(senderDatasetId, 
					this.getVersion());
		}
		
		if (senderDatasetId == null) {
			throw new ReportException("Cannot retrieve the report sender id for " + this);
		}
		
		DatasetList<Dataset> datasets = request.getList();

		return datasets.filterBySenderId(senderDatasetId);
	}
	
	/**
	 * Get the dataset related to this report (only metadata!). Note that only the newer one will
	 * be returned. If you need all the datasets related to this report use
	 * {@link #getDatasets()}.
	 * @return
	 * @throws MySOAPException
	 * @throws ReportException
	 */
	public Dataset getDataset() throws MySOAPException, ReportException {

		DatasetList<Dataset> datasets = getDatasets();

		if(datasets.isEmpty())
			return null;
		
		return datasets.get(0);
	}
	
	@Override
	public File export(MessageConfigBuilder messageConfig)
			throws IOException, ParserConfigurationException, SAXException, ReportException {
		
		if (messageConfig.needEmptyDataset())
			return ReportXmlBuilder.createEmptyReport(messageConfig);
		else {
			
			// get the previous report version to process amendments
			ReportXmlBuilder creator = new ReportXmlBuilder(this, messageConfig, getRowIdFieldName());
			return creator.exportReport();
		}
	}
	
	/**
	 * Export the report and send it to the DCF
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws SendMessageException
	 * @throws ReportException 
	 * @throws SOAPException
	 */
	public void exportAndSend(MessageConfigBuilder messageConfig) throws IOException, ParserConfigurationException, 
		SAXException, SendMessageException, MySOAPException, ReportException {

		// export the report and get an handle to the exported file
		File file = this.export(messageConfig);

		try {
			
			this.send(file, messageConfig.getOpType());
			
			// delete file also if exception occurs
			if (!DebugConfig.debug)
				file.delete();
		}
		catch (SOAPException e) {

			// delete file also if exception occurs
			if (!DebugConfig.debug)
				file.delete();

			throw new MySOAPException(e);
		}
	}
	
	/**
	 * Get an acknowledgement of the dataset related to the report
	 * @return
	 * @throws SOAPException
	 */
	public Ack getAck() throws MySOAPException {

		// make get ack request
		String messageId = this.getMessageId();
		
		// if no message id => the report was never sent
		if (messageId.isEmpty()) {
			return null;
		}
		
		GetAck req = new GetAck(messageId);
		
		// get state
		Ack ack = req.getAck();
		
		return ack;
	}

	public DatasetStatus updateStatusWithAck() throws MySOAPException {
		
		Ack ack = this.getAck();
		
		// if we have something in the ack
		if (ack.isReady()) {

			// save id
			String datasetId = ack.getLog().getDatasetId();
			this.setDatasetId(datasetId);
			
			// save status
			DatasetStatus status = ack.getLog().getDatasetStatus();
			this.setStatus(status);
			
			// permanently save data
			this.update();
			
			System.out.println("Ack successful for message id " + this.getMessageId() + ". Retrieved datasetId=" 
					+ datasetId + " with status=" + this.getStatus());
		}
		
		return this.getStatus();
	}

	public String getMessageId() {
		return this.getCode(AppPaths.REPORT_MESSAGE_ID);
	}
	
	public void setMessageId(String id) {
		this.put(AppPaths.REPORT_MESSAGE_ID, id);
	}
	
	public String getDatasetId() {
		return this.getCode(AppPaths.REPORT_DATASET_ID);
	}
	
	public void setDatasetId(String id) {
		this.put(AppPaths.REPORT_DATASET_ID, id);
	}
	
	/**
	 * Get the version contained in the sender id
	 * @return
	 */
	public String getVersion() {
		return this.getCode(AppPaths.REPORT_VERSION);
	}
	
	public void setVersion(String version) {
		this.put(AppPaths.REPORT_VERSION, version);
	}
	
	public String getSenderId() {
		return this.getCode(AppPaths.REPORT_SENDER_ID);
	}
	
	public void setSenderId(String id) {
		this.put(AppPaths.REPORT_SENDER_ID, id);
	}

	/**
	 * Get the status of the dataset attached to the report
	 * @return
	 */
	public DatasetStatus getStatus() {
		String status = getCode(AppPaths.REPORT_STATUS);
		return DatasetStatus.fromString(status);
	}
	
	public void setStatus(String status) {
		this.put(AppPaths.REPORT_STATUS, status);
	}
	
	public void setStatus(DatasetStatus status) {
		this.put(AppPaths.REPORT_STATUS, status.getStatus());
	}
	
	public String getYear() {
		return this.getCode(AppPaths.REPORT_YEAR);
	}
	
	public void setYear(String year) {
		this.put(AppPaths.REPORT_YEAR, 
				getTableColumnValue(year, AppPaths.YEARS_LIST));
	}
	
	public String getMonth() {
		return this.getCode(AppPaths.REPORT_MONTH);
	}
	
	public void setMonth(String month) {
		this.put(AppPaths.REPORT_MONTH, 
				getTableColumnValue(month, AppPaths.MONTHS_LIST));
	}
	
	@Override
	public DatasetStatus alignStatusWithDCF() throws MySOAPException, ReportException {
		
		// get the dataset related to the report from the
		// GetDatasetList request
		Dataset dataset = this.getDataset();
		
		System.out.println(dataset);
		// if not dataset is retrieved
		if (dataset == null) {
			return this.getStatus();
		}
		
		// if equal, ok
		if (dataset.getStatus() == this.getStatus())
			return this.getStatus();
		
		// if the report is submitted
		if (this.getStatus() == DatasetStatus.SUBMITTED) {
			
			// and dataset is accepted dwh or rejected editable
			switch(dataset.getStatus()) {
			case ACCEPTED_DWH:
			case REJECTED_EDITABLE:
				// update local report status with the dcf status
				this.setStatus(dataset.getStatus());
				break;
			default:
				break;
			}
		}
		else {
			
			// if not in status submitted
			
			switch(dataset.getStatus()) {
			// if deleted/rejected then make the report editable
			case DELETED:
			case REJECTED:
				
				// put the report in draft (status automatically changed)
				this.makeEditable();
				break;
				
			// otherwise inconsistent status
			default:
				break;
			}
		}
		
		return this.getStatus();
	}
	
	/**
	 * Force the report to be editable
	 */
	public void makeEditable() {
		this.put(AppPaths.REPORT_STATUS, DatasetStatus.DRAFT.getStatus());
	}
	
	/**
	 * Check if the dataset can be edited or not
	 * @return
	 */
	public boolean isEditable() {
		return getStatus().isEditable();
	}
	
	/**
	 * Delete all the versions of the report from the database
	 * @return
	 */
	public boolean deleteAllVersions() {
		return deleteAllVersions(this.getSenderId());
	}
	
	/**
	 * Delete all the versions of the report from the db
	 * @param senderId
	 * @return
	 */
	public static boolean deleteAllVersions(String senderId) {
		// delete the old versions of the report (the one with the same senderId)
		TableDao dao = new TableDao(TableSchemaList.getByName(AppPaths.REPORT_SHEET));
		return dao.deleteByStringField(AppPaths.REPORT_SENDER_ID, senderId);
	}
	
	/**
	 * get the name of the field that contains the rowId.
	 * The rowId is the field that identifies a record
	 * of the report
	 * @return
	 */
	public abstract String getRowIdFieldName();
}
