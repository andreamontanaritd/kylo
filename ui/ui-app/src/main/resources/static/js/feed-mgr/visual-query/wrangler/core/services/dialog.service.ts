import {Injectable} from "@angular/core";
import {TdDialogService} from "@covalent/core/dialogs";
import "rxjs/add/operator/filter";
import {Observable} from "rxjs/Observable";

import {DateFormatConfig, DateFormatResponse, DialogService, CrossTabConfig, CrossTabResponse} from "../../api/services/dialog.service";
import {DateFormatDialog} from "../columns/date-format.component";
import {BinValuesConfig, BinValuesResponse, ImputeMissingConfig, ImputeMissingResponse, ReplaceValueEqualToConfig, ReplaceValueEqualToResponse} from "../../api";
import {ImputeMissingDialog} from "../columns/impute-missing.component";
import {DynamicFormDialogData} from "../../../../shared/dynamic-form/simple-dynamic-form/dynamic-form-dialog-data";
import {SimpleDynamicFormDialogComponent} from "../../../../shared/dynamic-form/simple-dynamic-form/simple-dynamic-form-dialog.component";
import {ColumnForm} from "../columns/column-form";

/**
 * Opens modal dialogs for alerting the user or receiving user input.
 */
@Injectable()
export class WranglerDialogService implements DialogService {

    constructor(private dialog: TdDialogService) {
    }

    /**
     * Opens a modal dialog for the user to input a date format string.
     *
     * @param config - dialog configuration
     * @returns the date format string
     */
    openDateFormat(config: DateFormatConfig): Observable<DateFormatResponse> {
        return this.dialog.open(DateFormatDialog, {data: config, panelClass: "full-screen-dialog"})
            .afterClosed()
            .filter(value => typeof value !== "undefined");
    }

    /**
     * Opens a modal dialog for the user to input a impute method
     *
     * @param config - dialog configuration
     * @returns the options selected
     */
    openImputeMissing(config: ImputeMissingConfig): Observable<ImputeMissingResponse> {
        return this.dialog.open(ImputeMissingDialog, {data: config, panelClass: "full-screen-dialog"})
            .afterClosed()
            .filter(value => typeof value !== "undefined");
    }

    openColumnForm(data:ColumnForm):Observable<any>{
      let dialogData:DynamicFormDialogData = new DynamicFormDialogData(data.formConfig)
      return  this.dialog.open(SimpleDynamicFormDialogComponent,{data:dialogData, panelClass: "full-screen-dialog",height:'100%',width:'400px',position:{top:'0',right:'0'}})
            .afterClosed()
            .filter(value => typeof value !== "undefined");
    }


}
