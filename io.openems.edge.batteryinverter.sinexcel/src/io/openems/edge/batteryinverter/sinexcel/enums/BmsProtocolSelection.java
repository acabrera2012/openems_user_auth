package io.openems.edge.batteryinverter.sinexcel.enums;

import io.openems.common.types.OptionsEnum;

public enum BmsProtocolSelection implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	DISABLED(0, "Disable"), //
	POWERWISE(1, "PowerWise"), //
	KGOOER(2, "KGOOER"), //
	ALPHA(3, "Alpha-Ess"), //
	ALPHA_CAN(21, "Alpha-Ess CAN"), //
	PYLONTECH_CAN(22, "Pylontech CAN"), //
	GOLDELEC_CAN(23, "Gold Elec. CAN"), //
	SINEXCEL_CAN(24, "Sinexcel CAN"); //

	private final int value;
	private final String name;

	private BmsProtocolSelection(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}