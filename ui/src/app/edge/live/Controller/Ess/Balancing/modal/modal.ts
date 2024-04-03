import { Component } from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { DefaultTypes } from 'src/app/shared/service/defaulttypes';

import { AbstractModal } from 'src/app/shared/genericComponents/modal/abstractModal';
import { ChannelAddress, CurrentData, Utils } from 'src/app/shared/shared';

@Component({
  templateUrl: './modal.html'
})
export class ModalComponent extends AbstractModal {

  public chargeDischargePower: { name: string, value: number };

  public readonly CONVERT_TO_WATT = Utils.CONVERT_TO_WATT;
  public readonly CONVERT_MANUAL_ON_OFF = Utils.CONVERT_MANUAL_ON_OFF(this.translate);
  public propertyMode: DefaultTypes.ManualOnOff = null;

  protected override getChannelAddresses(): ChannelAddress[] {
    return [
      new ChannelAddress(this.component.id, "_PropertyTargetGridSetpoint")
    ];
  }

  protected override onCurrentData(currentData: CurrentData) {

    this.chargeDischargePower = Utils.convertChargeDischargePower(this.translate, currentData.allComponents[this.component.id + '/_PropertyTargetGridSetpoint']);
  }

  protected override getFormGroup(): FormGroup {
    return this.formBuilder.group({
      mode: new FormControl(this.component.properties.mode),
      targetGridSetpoint: new FormControl(this.component.properties.targetGridSetpoint)
    });
  }
}
