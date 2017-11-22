package report;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import acknowledge.Ack;
import acknowledge.AckLog;
import acknowledge.OpResError;
import app_config.AppPaths;
import app_config.PropertiesReader;
import dataset.DatasetStatus;
import global_utils.Warnings;
import html_viewer.HtmlViewer;
import webservice.MySOAPException;

public class ReportAckManager {

	private Shell shell;
	private EFSAReport report;

	/**
	 * Perform ack operations regarding the chosen report
	 * @param shell
	 * @param report
	 */
	public ReportAckManager(Shell shell, EFSAReport report) {
		this.shell = shell;
		this.report = report;
	}

	/**
	 * Refresh the status of a report
	 * @param shell
	 * @param report
	 * @param listener
	 */
	public void refreshStatus(Listener listener) {

		// if the report status is not able of
		// getting an ack, then simply align its status
		// with the one in dcf
		if (!report.getStatus().canGetAck()) {
			alignReportStatusWithDCF(listener);
			return;
		}

		// else if local status UPLOADED, SUBMISSION_SENT, REJECTION_SENT

		// get the ack of the dataset
		Ack ack = this.getAck(true, listener);

		if (ack == null)  // error occurred
			return;

		// if ack is ready then check if the report status
		// is the same as the one in the get dataset list
		if (ack.isReady() && ack.getLog().isOk()) {
			
			AckLog log = ack.getLog();

			// if no dataset retrieved for the current report
			if (!log.getDatasetStatus().existsInDCF()) {

				// warn the user, the ack cannot be retrieved yet
				String title = "Acknowledgment received";
				String message = "ERR804: The status is not valid, found status " + log.getDatasetStatus();
				int style = SWT.ICON_ERROR;
				Warnings.warnUser(shell, title, message, style);

				return;
			}

			alignReportStatusWithDCF(listener);
		}
		else {

			// warn the user, the ack cannot be retrieved yet
			String title = "Warning";
			String message = "WARN501: Dataset still in processing.";
			int style = SWT.ICON_INFORMATION;
			Warnings.warnUser(shell, title, message, style);
		}
	}


	/**
	 * Display an ack in the browser
	 * @param shell
	 * @param report
	 */
	public void displayAck() {
		
		String messageId = report.getMessageId();
		
		// if no message id found
		if (messageId == null || messageId.isEmpty()) {
			Warnings.warnUser(shell, "Error", 
					"ERR800: The ack message is not available within the tool, please check on DCF.");
			return;
		}
		
		Ack ack = this.getAck(false, null);
		
		if (ack == null || ack.getLog() == null)
			return;
		
		// get the raw log to send the .xml to the browser
		InputStream rawLog = ack.getLog().getRawLog();
		
		// write it into a file in the temporary folder
		// in order to be able to open it in the browser
		String filename = AppPaths.TEMP_FOLDER + "ack_" + System.currentTimeMillis() + ".xml";
		File targetFile = new File(filename);
		
		try {
			
			Files.copy(rawLog, targetFile.toPath());
			
			// close input stream
			rawLog.close();
			
		} catch (IOException e) {
			e.printStackTrace();
			Warnings.warnUser(shell, "Error", 
					"ERR802: Cannot process the acknowledgement.");
		}
		
		// open the ack in the browser to see it formatted
		HtmlViewer viewer = new HtmlViewer();
		viewer.open(targetFile);
	}

	/**
	 * Get an acknowledge of the report
	 * @param shell
	 * @param report
	 * @return
	 */
	public Ack getAck(boolean updateReportStatus, Listener updateListener) {

		shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

		boolean errorOccurred = false;
		String title = "Error";
		String message = null;
		int style = SWT.ERROR;

		Ack ack = null;

		try {

			ack = report.getAck();

			if (ack == null) {
				message = "ERR803: No acknowledgment was found for the report.";
				errorOccurred = true;
			}
			else {

				if (ack.getLog() != null) {
					OpResError error = ack.getLog().getOpResError();
					switch(error) {
					case NOT_EXISTING_DC:
						message = "ERR407: The data collection " + PropertiesReader.getDataCollectionCode() 
						+ " is not a valid one. Please contact zoonoses_support@efsa.europa.eu.";
						errorOccurred = true;
						break;
					case USER_NOT_AUTHORIZED:
						message = "ERR101: Your account is not authorized for the data collection " 
								+ PropertiesReader.getDataCollectionCode();
						errorOccurred = true;
						break;
					default:
						break;
					}
				}
			}
			
			// update the report status if required
			if(!errorOccurred && updateReportStatus) {
				report.updateStatusWithAck();
			}

			// update the ui accordingly
			if (!errorOccurred && updateListener != null)
				updateListener.handleEvent(null);

		} catch (MySOAPException e) {
			e.printStackTrace();
			String[] warning = Warnings.getSOAPWarning(e);
			title = warning[0];
			message = warning[1];
			style = SWT.ERROR;
		}
		finally {
			shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
		}

		// if a message needs to be shown
		if (message != null) {
			Warnings.warnUser(shell, title, message, style);
			return null;
		}

		return ack;
	}


	/**
	 * Update the report status with the dataset contained in the DCF
	 * @param shell
	 * @param report
	 * @param updateListener
	 * @throws ReportException 
	 * @throws MySOAPException 
	 */
	public void alignReportStatusWithDCF(Listener updateListener) {

		String title = null;
		String message = null;
		int style = SWT.ERROR;

		try {

			shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

			DatasetStatus oldStatus = report.getStatus();
			DatasetStatus newStatus = report.alignStatusWithDCF();

			// if we have the same status then ok stop
			// we have the report updated
			if (oldStatus == newStatus) {
				title = "Acknowledgment received";
				message = "Current report state: " + newStatus;
				style = SWT.ICON_INFORMATION;
			}

			// if the report was in status submitted
			// and in dcf ACCEPTED_DWH or REJECTED_EDITABLE
			else if (oldStatus == DatasetStatus.SUBMITTED) {

				// and dataset is accepted dwh or rejected editable
				switch(newStatus) {
				case ACCEPTED_DWH:
				case REJECTED_EDITABLE:

					title = "Acknowledgment received";
					message = "Current report state: " + newStatus;
					style = SWT.ICON_INFORMATION;
					break;
				default:
					break;
				}
			}
			else {

				// otherwise if report is not in status submitted
				// check dcf status
				switch(newStatus) {
				case DELETED:
				case REJECTED:

					title = "Warning";
					message = "WARN501: The related dataset in DCF is in status " 
							+ newStatus 
							+ ". Local report status will be changed to " 
							+ DatasetStatus.DRAFT;

					style = SWT.ICON_WARNING;

					break;

					// otherwise inconsistent status
				default:

					title = "Error";
					message = "ERR501: The related dataset in DCF is in status " 
							+ newStatus
							+ ". Status of local report is inconsistent. Please contact zoonoses_support@efsa.europa.eu.";

					style = SWT.ICON_ERROR;

					break;
				}
			}
		}
		catch (MySOAPException e) {
			e.printStackTrace();
			String[] warning = Warnings.getSOAPWarning(e);
			title = warning[0];
			message = warning[1];
			style = SWT.ERROR;

		} catch (ReportException e) {
			e.printStackTrace();
			title = "Error";
			message = "ERR700: The dataset of the report cannot be retrieved since the report lacks of datasetSenderId.";
			style = SWT.ERROR;
		}
		finally {
			shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
		}

		// call the listener
		updateListener.handleEvent(null);

		// if we have an error show it and stop the process
		if (message != null) {
			Warnings.warnUser(shell, title, message, style);
		}
	}
}