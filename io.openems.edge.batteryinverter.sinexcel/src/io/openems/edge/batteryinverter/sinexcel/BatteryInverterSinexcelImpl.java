package io.openems.edge.batteryinverter.sinexcel;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2_AND_INVERT;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.NEGATIVE_SCALE_FACTOR_2;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.chain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OptionsEnum;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.batteryinverter.sinexcel.enums.EnableDisable;
import io.openems.edge.batteryinverter.sinexcel.enums.FrequencyLevel;
import io.openems.edge.batteryinverter.sinexcel.enums.GridCodeSelection;
import io.openems.edge.batteryinverter.sinexcel.enums.PowerRisingMode;
import io.openems.edge.batteryinverter.sinexcel.enums.VoltageLevel;
import io.openems.edge.batteryinverter.sinexcel.statemachine.Context;
import io.openems.edge.batteryinverter.sinexcel.statemachine.StateMachine;
import io.openems.edge.batteryinverter.sinexcel.statemachine.StateMachine.State;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
//import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
//import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Battery-Inverter.Sinexcel", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
})
public class BatteryInverterSinexcelImpl extends AbstractOpenemsModbusComponent
		implements BatteryInverterSinexcel, OffGridBatteryInverter, ManagedSymmetricBatteryInverter,
		SymmetricBatteryInverter, ModbusComponent, OpenemsComponent, TimedataProvider, StartStoppable {

	public static final int MAX_APPARENT_POWER = 30_000;
	public static final int DEFAULT_UNIT_ID = 1;
	public static final int DEFAULT_EMS_TIMEOUT = 60;
	public static final int DEFAULT_BMS_TIMEOUT = 0;
	public static final EnableDisable DEFAULT_GRID_EXISTENCE_DETECTION_ON = EnableDisable.DISABLE;
	public static final PowerRisingMode DEFAULT_POWER_RISING_MODE = PowerRisingMode.STEP;

	private static final int MAX_CURRENT = 90; // [A]
	private static final int MAX_TOPPING_CHARGE_VOLTAGE = 750;

	private final Logger log = LoggerFactory.getLogger(BatteryInverterSinexcelImpl.class);
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	protected Config config;

	public BatteryInverterSinexcelImpl() throws OpenemsNamedException {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				OffGridBatteryInverter.ChannelId.values(), //
				BatteryInverterSinexcel.ChannelId.values() //
		);
		this._setMaxApparentPower(BatteryInverterSinexcelImpl.MAX_APPARENT_POWER);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), DEFAULT_UNIT_ID, this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run(Battery battery, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		// Store the current State
		this.channel(BatteryInverterSinexcel.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());

		// Initialize 'Start-Stop' Channel
		this._setStartStop(StartStop.UNDEFINED);

		// Set Default Settings
		this.setDefaultSettings();

		// Set Battery Limits
		this.setBatteryLimits(battery);

		// Calculate the Energy values from ActivePower.
		this.calculateEnergy();

		// Prepare Context
		var context = new Context(this, this.config, this.targetGridMode.get(), setActivePower, setReactivePower);

		// Call the StateMachine
		try {
			this.stateMachine.run(context);

			this.channel(BatteryInverterSinexcel.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(BatteryInverterSinexcel.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	/**
	 * Updates the Channel if its current value is not equal to the new value.
	 *
	 * @param channelId Sinexcel Channel-Id
	 * @param value     {@link OptionsEnum} value.
	 * @throws IllegalArgumentException on error
	 */
	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, OptionsEnum value)
			throws IllegalArgumentException, OpenemsNamedException {
		this.updateIfNotEqual(channelId, value.getValue());
	}

	/**
	 * Updates the Channel if its current value is not equal to the new value.
	 *
	 * @param channelId Sinexcel Channel-Id
	 * @param newValue  Integer value.
	 * @throws IllegalArgumentException on error
	 */
	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, Integer newValue)
			throws IllegalArgumentException {
		WriteChannel<Integer> channel = this.channel(channelId);
		var currentValue = channel.value();
		if (currentValue.isDefined() && !Objects.equals(currentValue.get(), newValue)) {
			try {
				channel.setNextWriteValue(newValue);
			} catch (OpenemsNamedException e) {
				this.logWarn(this.log, "Unable to update Channel [" + channel.address() + "] from [" + currentValue
						+ "] to [" + newValue + "]");
				e.printStackTrace();
			}
		}
	}

	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, VoltageLevel voltageLevel)
			throws IllegalArgumentException, OpenemsNamedException {
		IntegerWriteChannel channel = this.channel(channelId);
		channel.setNextWriteValue(voltageLevel.getValue());
	}

	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, FrequencyLevel frequencyLevel)
			throws IllegalArgumentException, OpenemsNamedException {
		IntegerWriteChannel channel = this.channel(channelId);
		channel.setNextWriteValue(frequencyLevel.getValue());
	}

	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, GridCodeSelection gridCodeSelection)
			throws IllegalArgumentException, OpenemsNamedException {
		IntegerWriteChannel channel = this.channel(channelId);
		channel.setNextWriteValue(gridCodeSelection.getValue());
	}

	private void updateIfNotEqual(BatteryInverterSinexcel.ChannelId channelId, EnableDisable value)
			throws IllegalArgumentException, OpenemsNamedException {
		BooleanWriteChannel channel = this.channel(channelId);
		switch (value) {
		case ENABLE:
			channel.setNextWriteValue(true);
			break;
		case DISABLE:
			channel.setNextWriteValue(false);
			break;
		}
	}

	/**
	 * Sets some default settings on the inverter, like Timeout.
	 *
	 * @throws OpenemsNamedException on error
	 */
	private void setDefaultSettings() throws OpenemsNamedException {
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.EMS_TIMEOUT, DEFAULT_EMS_TIMEOUT);
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.BMS_TIMEOUT, DEFAULT_BMS_TIMEOUT);
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON,
				DEFAULT_GRID_EXISTENCE_DETECTION_ON);
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.POWER_RISING_MODE, DEFAULT_POWER_RISING_MODE);

		switch (this.config.countryCode()) {
		case AUSTRIA:
		case GERMANY:
		case SWITZERLAND:
			this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.VOLTAGE_LEVEL, VoltageLevel.V_400);
			this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.FREQUENCY_LEVEL, FrequencyLevel.HZ_50);
			this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.GRID_CODE_SELECTION, GridCodeSelection.VDE);
			break;
		}

		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.INVERTER_WIRING_TOPOLOGY, this.config.emergencyPower());
	}

	/**
	 * Sets the Battery Limits.
	 *
	 * @param battery the linked {@link Battery}
	 * @throws OpenemsNamedException on error
	 */
	private void setBatteryLimits(Battery battery) throws OpenemsNamedException {
		// Upper voltage limit of battery protection >= Topping charge voltage >= Float
		// charge voltage >= Lower voltage limit of battery protection (814 >= 809 >=
		// 808 >= 813).
		// Discharge Min Voltage
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE,
				battery.getDischargeMinVoltage().get());

		// Charge Max Voltage
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_VOLTAGE,
				battery.getChargeMaxVoltage().get());

		// Topping Charge Voltage
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE,
				TypeUtils.min(battery.getChargeMaxVoltage().get(), MAX_TOPPING_CHARGE_VOLTAGE));

		// Float Charge Voltage
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE,
				TypeUtils.min(battery.getChargeMaxVoltage().get(), MAX_TOPPING_CHARGE_VOLTAGE));

		// Discharge Max Current
		// negative value is corrected as zero
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.DISCHARGE_MAX_CURRENT,
				TypeUtils.fitWithin(0 /* enforce positive */, MAX_CURRENT, battery.getDischargeMaxCurrent().orElse(0)));

		// Charge Max Current
		// negative value is corrected as zero
		this.updateIfNotEqual(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_CURRENT,
				TypeUtils.fitWithin(0 /* enforce positive */, MAX_CURRENT, battery.getChargeMaxCurrent().orElse(0)));
	}

	@Override
	public String debugLog() {
		return new StringBuilder()
				.append(this.stateMachine.debugLog()) //
				.append("|Grid:").append(this.getGridModeChannel().value().asOptionString()) //
				.toString();
	}

	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

	@Override
	public void setStartStop(StartStop value) {
		if (this.startStopTarget.getAndSet(value) != value) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	/**
	 * Gets the inverter start-stop target.
	 * 
	 * @return {@link StartStop}
	 */
	public StartStop getStartStopTarget() {
		switch (this.config.startStop()) {
		case AUTO:
			// read StartStop-Channel
			return this.startStopTarget.get();

		case START:
			// force START
			return StartStop.START;

		case STOP:
			// force STOP
			return StartStop.STOP;
		}

		assert false;
		return StartStop.UNDEFINED; // can never happen
	}

	protected final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);

	@Override
	public void setTargetGridMode(TargetGridMode targetGridMode) {
		if (this.targetGridMode.getAndSet(targetGridMode) != targetGridMode) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	@Override
	public BatteryInverterConstraint[] getStaticConstraints() throws OpenemsException {
		if (this.stateMachine.getCurrentState() == State.RUNNING) {
			return BatteryInverterConstraint.NO_CONSTRAINTS;

		}
		// Block any power as long as we are not RUNNING
		return new BatteryInverterConstraint[] { //
				new BatteryInverterConstraint("Sinexcel inverter not ready", Phase.ALL, Pwr.REACTIVE, //
						Relationship.EQUALS, 0d), //
				new BatteryInverterConstraint("Sinexcel inverter not ready", Phase.ALL, Pwr.ACTIVE, //
						Relationship.EQUALS, 0d) //
		};
	}

	@Override
	public int getPowerPrecision() {
		return 100;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0, Priority.LOW, //
						m(BatteryInverterSinexcel.ChannelId.SERIAL_NUMBER, new StringWordElement(0, 11)), //
						m(BatteryInverterSinexcel.ChannelId.MANUFACTURER_AND_MODEL_NUMBER, new StringWordElement(11, 22))), //
				new FC3ReadRegistersTask(32, Priority.HIGH, //
						m(new BitsWordElement(32, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.FAULT_STATUS) // /* fault status */
								.bit(1, BatteryInverterSinexcel.ChannelId.ALERT_STATUS) // /* alarm status */
								.bit(2, BatteryInverterSinexcel.ChannelId.DERATING_STATUS) // /* system derating status */

								// next one is dummied as I don't know it's value and bit reading has to be contiguour
								// i don't know if this is going to work
								// .bit(3, BatteryInverterSinexcel.ChannelId.unknown) // /* **NEW** system booting status */
								// .bit(3, BatteryInverterSinexcel.ChannelId.DummyRegisterElement) // /* dummy bit */ dummy bit

								.bit(4, BatteryInverterSinexcel.ChannelId.INVERTER_GRID_MODE) // /* system grid-tied status 1=on-grid */
								.bit(5, BatteryInverterSinexcel.ChannelId.ISLAND_MODE))), // /* system off-grid status 1=off-grid */

								// next one needs to be set to true... how to do this?
								// .bit(6, BatteryInverterSinexcel.ChannelId.ALLOW_GRID_CONNECTION) // **OLD** <------ NEED TO SET THIS TO TRUE (or find a value for it),***************************

								// .bit(9, BatteryInverterSinexcel.ChannelId.unknown) // **NEW** /* system failed status 1=failed */

				new FC3ReadRegistersTask(33, Priority.LOW, //
						m(new BitsWordElement(33, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.BATTERY_INVERTER_STATE) // /* system on/off status */
								.bit(1, BatteryInverterSinexcel.ChannelId.STANDBY_STATUS) // /* system standby status */
								.bit(2, BatteryInverterSinexcel.ChannelId.DC_CHARGING) // /* system charging status */
								.bit(3, BatteryInverterSinexcel.ChannelId.DC_DISCHARGING) // /* system discharging status */
								.bit(4, BatteryInverterSinexcel.ChannelId.BATTERY_FULLY_CHARGED) // /*system fully charged */
								.bit(5, BatteryInverterSinexcel.ChannelId.BATTERY_EMPTY))), // /* system fully discharged */
								// .bit(6, BatteryInverterSinexcel.ChannelId.unknown) // **NEW** /* system constant volt topping charging */
								// .bit(0, BatteryInverterSinexcel.ChannelId.OBTAIN_FAULT_RECORD_FLAG) // **OLD**

								// more old value for which I don't have a value
								// .bit(1, BatteryInverterSinexcel.ChannelId.WRITE_POWER_GENERATION_INTO_EEPROM) // **OLD**
								// .bit(2, BatteryInverterSinexcel.ChannelId.INITIALIZE_DSP_PARAMETERS) // **OLD**
								// .bit(3, BatteryInverterSinexcel.ChannelId.MASTER_SLAVE_MODE)), // **OLD**

				new FC3ReadRegistersTask(106, Priority.LOW, //
						m(new BitsWordElement(106, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.EPO_ERROR ) // /* was 37,0 */
								// MORE gaps in bits which I don't know how to deal with
                // maybe it's not a problem... time will tell

								.bit(4, BatteryInverterSinexcel.ChannelId.INITIALIZE_DSP_PARAMETERS) // /* was 33,2 */

								.bit(6, BatteryInverterSinexcel.ChannelId.DSP_VERSION_ABNORMAL ) // /* was 37,2 */
								.bit(7, BatteryInverterSinexcel.ChannelId.CPLD_VERSION_ERROR ) // /* was 37,3 */
								.bit(8, BatteryInverterSinexcel.ChannelId.HARDWARE_VERSION_ERROR ) // /* was 37,4 */

								.bit(12, BatteryInverterSinexcel.ChannelId.MONITOR_PARAMETER_MISMATCH ))), // /* was 37,1 */

				new FC3ReadRegistersTask(107, Priority.LOW, //
						m(new BitsWordElement(107, this) //

								.bit(1, BatteryInverterSinexcel.ChannelId.CANA_COMMUNICATION_FAILURE) // /* was 37,5 */
								.bit(2, BatteryInverterSinexcel.ChannelId.CANB_COMMUNICATION_FAILURE) // /* was 36,13 */

								.bit(8, BatteryInverterSinexcel.ChannelId.EMS_COMMUNICATION_TIMEOUT) // /* was 44,5 */
								.bit(9, BatteryInverterSinexcel.ChannelId.BMS_COMMUNICATION_TIMEOUT))), // /* was 44,4 */

				new FC3ReadRegistersTask(108, Priority.LOW, //
						m(new BitsWordElement(108, this) //

								.bit(1, BatteryInverterSinexcel.ChannelId.FAN_FAILURE) // /* was 37,7 */

								.bit(11, BatteryInverterSinexcel.ChannelId.AUXILARY_POWER_FAULT))), // /* was 37,6 */
								// should be orded with below ones to make AUXILARY_POWER_FAULT
								// .bit(12, BatteryInverterSinexcel.) // /* was 37,5 */
								// .bit(13, BatteryInverterSinexcel.) // /* was 37,5 */

				new FC3ReadRegistersTask(109, Priority.LOW, //
						m(new BitsWordElement(109, this) //
		
								.bit(9, BatteryInverterSinexcel.ChannelId.INTERNAL_AIR_OVER_TEMPERATURE))), // /* was 36,10 */

				new FC3ReadRegistersTask(114, Priority.LOW, //
						m(new BitsWordElement(114, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.DC_INPUT_OVER_VOLTAGE_PROTECTION) // /* was 44,0 */
								.bit(1, BatteryInverterSinexcel.ChannelId.DC_INPUT_UNDER_VOLTAGE_PROTECTION) // /* was 44,1 */

								.bit(5, BatteryInverterSinexcel.ChannelId.DC_SOFT_START_FAILED) // /* was 45,0 */
								.bit(6, BatteryInverterSinexcel.ChannelId.DC_RELAY_SHORT_OPEN) // /* was 45,2 */
								.bit(7, BatteryInverterSinexcel.ChannelId.DC_RELAY_SHORT_CIRCUIT))), // /* was 45,1 */
		
				new FC3ReadRegistersTask(115, Priority.LOW, //
						m(new BitsWordElement(115, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.DC_OVER_VOLTAGE ) // /* was 37,8 */
								.bit(1, BatteryInverterSinexcel.ChannelId.DC_LOW_VOLTAGE ) // /* was 37,9 */
								.bit(2, BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_UNBALANCED ) // /* was 37,10 */
								.bit(3, BatteryInverterSinexcel.ChannelId.DC_BUS_STARTING_FAILED ) // /* was 45,4 */

								.bit(9, BatteryInverterSinexcel.ChannelId.BMS_ALERT ))), // /* was 44,3 */

				new FC3ReadRegistersTask(120, Priority.LOW, //
						m(new BitsWordElement(120, this) //
								.bit(0, BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_PROTECTION ) // /* was 36,0 */
								.bit(1, BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_PROTECTION ) // /* was 36,1 */
								.bit(2, BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_PROTECTION ) // /* was 36,2 */
								.bit(3, BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_PROTECTION ) // /* was 36,3 */
								.bit(4, BatteryInverterSinexcel.ChannelId.GRID_PHASE_REVERSE ) // /* was 36,5 */
								.bit(5, BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_UNBALANCE ) // /* was 36,4 */

								.bit(7, BatteryInverterSinexcel.ChannelId.GRID_PHASE_LOCK_FAILED ) // /* was 36,9 */
								.bit(8, BatteryInverterSinexcel.ChannelId.INVERTER_ISLAND ))), // /* was 36,6 */
		
				new FC3ReadRegistersTask(121, Priority.LOW, //
						m(new BitsWordElement(121, this) //
								
								.bit(6, BatteryInverterSinexcel.ChannelId.ON_GRID_OFF_GRID_SWITCH_OVER_FAILURE ) // /* was 36,7 */
								.bit(7, BatteryInverterSinexcel.ChannelId.AC_SOFT_START_FAILURE ) // /* was 38,2 */
								.bit(8, BatteryInverterSinexcel.ChannelId.AC_RELAY_IS_OPEN ) // /* was 38,4 */
								.bit(9, BatteryInverterSinexcel.ChannelId.AC_RELAY_SHORT_CIRCUIT ))), // /* was 37,12 */

        /*******************************************************************************************************************/

        new FC3ReadRegistersTask(156, Priority.HIGH, //
            m(BatteryInverterSinexcel.ChannelId.TEMPERATURE_OF_AC_HEAT_SINK, new SignedWordElement(156)), // /* was 132 */
						new DummyRegisterElement(157, 163),
						m(BatteryInverterSinexcel.ChannelId.COS_PHI, new SignedWordElement(164), SCALE_FACTOR_MINUS_2), // /* was 125 */
            m(BatteryInverterSinexcel.ChannelId.COS_PHI_L1, new SignedWordElement(165),	SCALE_FACTOR_MINUS_2), // /* was 119 */
            m(BatteryInverterSinexcel.ChannelId.COS_PHI_L2, new SignedWordElement(166),	SCALE_FACTOR_MINUS_2), // /* was 120 */
            m(BatteryInverterSinexcel.ChannelId.COS_PHI_L3, new SignedWordElement(167),	SCALE_FACTOR_MINUS_2)), // /* was 121 */
        new FC3ReadRegistersTask(168, Priority.HIGH, //
        				m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, new SignedWordElement(168), SCALE_FACTOR_2_AND_INVERT), // /* was 122 */
						//m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, new SignedWordElement(168), chain(SCALE_FACTOR_2, IGNORE_LESS_THAN_100)), // /* was 122 */
						m(BatteryInverterSinexcel.ChannelId.ACTIVE_POWER_L1, new SignedWordElement(169),	SCALE_FACTOR_2_AND_INVERT), // /* was 110 */
						m(BatteryInverterSinexcel.ChannelId.ACTIVE_POWER_L2, new SignedWordElement(170),	SCALE_FACTOR_2_AND_INVERT), // /* was 111 */
						m(BatteryInverterSinexcel.ChannelId.ACTIVE_POWER_L3, new SignedWordElement(171),	SCALE_FACTOR_2_AND_INVERT), // /* was 112 */
						m(SymmetricBatteryInverter.ChannelId.REACTIVE_POWER, new SignedWordElement(172),	SCALE_FACTOR_1), // /* was 123 */
						m(BatteryInverterSinexcel.ChannelId.REACTIVE_POWER_L1, new SignedWordElement(173),	SCALE_FACTOR_1), // /* was 113 */
						m(BatteryInverterSinexcel.ChannelId.REACTIVE_POWER_L2, new SignedWordElement(174),	SCALE_FACTOR_1), // /* was 114 */
						m(BatteryInverterSinexcel.ChannelId.REACTIVE_POWER_L3, new SignedWordElement(175),	SCALE_FACTOR_1), // /* was 115 */
						m(BatteryInverterSinexcel.ChannelId.APPARENT_POWER, new SignedWordElement(176), SCALE_FACTOR_1), // /* was 124 */
						m(BatteryInverterSinexcel.ChannelId.APPERENT_POWER_L1, new SignedWordElement(177),	SCALE_FACTOR_1), // /* was 116 */
						m(BatteryInverterSinexcel.ChannelId.APPERENT_POWER_L2, new SignedWordElement(178),	SCALE_FACTOR_1), // /* was 117 */
						m(BatteryInverterSinexcel.ChannelId.APPERENT_POWER_L3, new SignedWordElement(179),	SCALE_FACTOR_1), // /* was 118 */
						m(BatteryInverterSinexcel.ChannelId.GRID_CURRENT_L1, new SignedWordElement(180),	SCALE_FACTOR_2), // /* was 104 */
						m(BatteryInverterSinexcel.ChannelId.GRID_CURRENT_L2, new SignedWordElement(181),	SCALE_FACTOR_2), // /* was 105 */
						m(BatteryInverterSinexcel.ChannelId.GRID_CURRENT_L3, new SignedWordElement(182),	SCALE_FACTOR_2)), // /* was 106 */
        new FC3ReadRegistersTask(183, Priority.HIGH, //
						m(BatteryInverterSinexcel.ChannelId.FREQUENCY, new SignedWordElement(183), SCALE_FACTOR_1), // /* was 107 */
     				new DummyRegisterElement(184, 186)), //
    		new FC3ReadRegistersTask(187, Priority.HIGH, //
						m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_L1, new SignedWordElement(187),	SCALE_FACTOR_2)), // /* was 101 */
		    new FC3ReadRegistersTask(188, Priority.HIGH, //
						m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_L2, new SignedWordElement(188),	SCALE_FACTOR_2)), // /* was 102 */
		    new FC3ReadRegistersTask(189, Priority.HIGH, //
						m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_L3, new SignedWordElement(189),	SCALE_FACTOR_2) // /* was 103 */
        ),

        /*******************************************************************************************************************/

				// 		m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_POSITIVE, new SignedWordElement(133),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_NEGATIVE, new SignedWordElement(134),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(135),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(136),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_VOLTAGE, new SignedWordElement(137),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_FREQUENCY, new SignedWordElement(138),	SCALE_FACTOR_1), //
				// 		new DummyRegisterElement(139, 140),
				// 		m(BatteryInverterSinexcel.ChannelId.DC_POWER, new SignedWordElement(141), SCALE_FACTOR_1), //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE, new SignedWordElement(142), SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_CURRENT, new SignedWordElement(143), SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_CHARGE_ENERGY, new UnsignedDoublewordElement(144),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_DISCHARGE_ENERGY, new UnsignedDoublewordElement(146),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.TEMPERATURE_OF_DC_DC_HEAT_SINK, new SignedWordElement(148)) //
				// ),

				// new FC3ReadRegistersTask(224, Priority.LOW, //
				// 		m(BatteryInverterSinexcel.ChannelId.DC_RELAY_REAR_END_VOLTAGE, new SignedWordElement(224),	SCALE_FACTOR_2), // 
				// 		m(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_CURRENT_READ, new UnsignedWordElement(225),	SCALE_FACTOR_2), //
				// 		m(BatteryInverterSinexcel.ChannelId.DISCHARGE_MAX_CURRENT_READ, new UnsignedWordElement(226),	SCALE_FACTOR_2), //
				// 		new DummyRegisterElement(227, 299),
				// 		m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_1, new UnsignedWordElement(300)), //
				// 		m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_2, new UnsignedWordElement(301)), //
				// 		m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_3, new UnsignedWordElement(302)), //
				// 		m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_4, new UnsignedWordElement(303)), //
				// 		m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_1, new UnsignedWordElement(304)), //
				// 		m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_2, new UnsignedWordElement(305)), //
				// 		m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_3, new UnsignedWordElement(306)), //
				// 		m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_4, new UnsignedWordElement(307)), //
				// 		m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_1, new UnsignedWordElement(308)), //
				// 		m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_2, new UnsignedWordElement(309)), //
				// 		m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_3, new UnsignedWordElement(310)), //
				// 		m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_4, new UnsignedWordElement(311)), //
				// 		m(BatteryInverterSinexcel.ChannelId.MAC, new StringWordElement(312, 3)), //
				// 		new DummyRegisterElement(315), //
				// 		m(BatteryInverterSinexcel.ChannelId.MODBUS_UNIT_ID, new UnsignedWordElement(316)), //
				// 		new DummyRegisterElement(317, 319),
				// 		m(BatteryInverterSinexcel.ChannelId.BAUDRATE, new UnsignedWordElement(320)), //
				// 		new DummyRegisterElement(321, 324),
				// 		m(BatteryInverterSinexcel.ChannelId.INTERFACE_TYPE, new UnsignedWordElement(325)), //
				// 		m(BatteryInverterSinexcel.ChannelId.COMMUNICATION_PROTOCOL_SELECTION,
				// new UnsignedWordElement(326)), //
				// 		m(BatteryInverterSinexcel.ChannelId.EMS_TIMEOUT, new UnsignedWordElement(327)), //
				// 		m(BatteryInverterSinexcel.ChannelId.EPO_ENABLE, new UnsignedWordElement(328)), //
				// 		m(BatteryInverterSinexcel.ChannelId.BMS_TIMEOUT, new UnsignedWordElement(329)), //
				// 		m(BatteryInverterSinexcel.ChannelId.BMS_PROTOCOL_SELECTION, new UnsignedWordElement(330)), //
				// 		m(BatteryInverterSinexcel.ChannelId.SET_GRID_MODE, new UnsignedWordElement(331)), //
				// 		m(BatteryInverterSinexcel.ChannelId.BUZZER_ENABLE, new UnsignedWordElement(332)), //
				// 		m(BatteryInverterSinexcel.ChannelId.RESTORE_FACTORY_SETTING, new UnsignedWordElement(333)) //
				// ),

				// new FC3ReadRegistersTask(650, Priority.LOW,
				// 		m(BatteryInverterSinexcel.ChannelId.START_INVERTER, new UnsignedWordElement(650)),
				// 		m(BatteryInverterSinexcel.ChannelId.STOP_INVERTER, new UnsignedWordElement(651)),
				// 		m(BatteryInverterSinexcel.ChannelId.CLEAR_FAILURE, new UnsignedWordElement(652)),
				// 		m(BatteryInverterSinexcel.ChannelId.SET_ON_GRID_MODE, new UnsignedWordElement(653)),
				// 		m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_MODE, new UnsignedWordElement(654)),
				// 		m(BatteryInverterSinexcel.ChannelId.SET_STANDBY_COMMAND, new UnsignedWordElement(655)),
				// 		m(BatteryInverterSinexcel.ChannelId.SET_SOFT_START, new UnsignedWordElement(656)),
				// 		m(BatteryInverterSinexcel.ChannelId.RESET_INSTRUCTION, new UnsignedWordElement(657)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_STOP, new UnsignedWordElement(658))
				// ),

				// new FC3ReadRegistersTask(748, Priority.LOW,
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_LEVEL, new UnsignedWordElement(748)),
				// 		m(BatteryInverterSinexcel.ChannelId.FREQUENCY_LEVEL, new UnsignedWordElement(749)),
				// 		m(BatteryInverterSinexcel.ChannelId.INVERTER_WIRING_TOPOLOGY, new UnsignedWordElement(750)),
				// 		new DummyRegisterElement(751),
				// 		m(BatteryInverterSinexcel.ChannelId.SWITCHING_DEVICE_ACCESS_SETTING,
        //   	new UnsignedWordElement(752)),
				// 		m(BatteryInverterSinexcel.ChannelId.MODULE_POWER_LEVEL, new UnsignedWordElement(753)),
				// 		m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_LEVEL, new UnsignedWordElement(754)),
				// 		m(BatteryInverterSinexcel.ChannelId.CPU_TYPE, new UnsignedWordElement(755)),
				// 		m(BatteryInverterSinexcel.ChannelId.OFF_GRID_AND_PARALLEL_ENABLE, new UnsignedWordElement(756)),
				// 		m(BatteryInverterSinexcel.ChannelId.SET_DC_SOFT_START_EXTERNAL_CONTROL, new UnsignedWordElement(757)),
				// 		new DummyRegisterElement(758, 767),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_OVER_VOLTAGE_PROTECTION_AMPLITUDE,	new SignedWordElement(768)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(769)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(770)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(771)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_1, new SignedWordElement(772)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(773)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(774)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(775)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_3, new SignedWordElement(776)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_3, new SignedWordElement(777)),

				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_1, new SignedWordElement(778)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(779)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_2, new SignedWordElement(780)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(781)),

				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_1,	new SignedWordElement(782)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(783)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_2,	new SignedWordElement(784)),
				// 		m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(785)),

				// 		m(BatteryInverterSinexcel.ChannelId.RECONNECT_TIME, new SignedWordElement(786)),
				// 		new DummyRegisterElement(787, 789),
				// 		m(BatteryInverterSinexcel.ChannelId.ANTI_ISLANDING, new UnsignedWordElement(790)),

				// 		m(BatteryInverterSinexcel.ChannelId.FREQUENCY_VOLTAGE_RIDE_THROUGH,	new UnsignedWordElement(791)),
				// 		m(BatteryInverterSinexcel.ChannelId.REACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(792)),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_RISING_MODE, new UnsignedWordElement(793)),
				// 		m(BatteryInverterSinexcel.ChannelId.ACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(794)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_ASYMMETRIC_DETECTON,	new UnsignedWordElement(795)),
				// 		m(BatteryInverterSinexcel.ChannelId.CONTINUOUS_OVERVOLTAGE_DETECTION,	new UnsignedWordElement(796)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON, new UnsignedWordElement(797)),
				// 		m(BatteryInverterSinexcel.ChannelId.NEUTRAL_FLOATING_DETECTION, new UnsignedWordElement(798)),
				// 		m(BatteryInverterSinexcel.ChannelId.OFF_GRID_BLACKSTART_MODE, new UnsignedWordElement(799)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_CODE_SELECTION, new UnsignedWordElement(800)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_CONNECTED_ACTIVE_CAPACITY_LIMITATION_FUNCTION,	new UnsignedWordElement(801)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_ACTIVE_POWER_CAPACITY_SETTING,	new UnsignedWordElement(802)),
				// 		m(BatteryInverterSinexcel.ChannelId.SINGLE_PHASE_MODE_SELECTION, new UnsignedWordElement(803)),
				// 		m(BatteryInverterSinexcel.ChannelId.OVER_VOLTAGE_DROP_ACTIVE, new UnsignedWordElement(804)),
				// 		m(BatteryInverterSinexcel.ChannelId.START_UP_MODE, new UnsignedWordElement(805)),
				// 		new DummyRegisterElement(806),
				// 		m(BatteryInverterSinexcel.ChannelId.LOCAL_ID_SETTING, new SignedWordElement(807)),
				// 		m(BatteryInverterSinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE, new SignedWordElement(808),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE, new SignedWordElement(809),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.CURRENT_FROM_TOPPING_CHARGING_TO_FLOAT_CHARGING,	new SignedWordElement(810), SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_CURRENT, new SignedWordElement(811),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.DISCHARGE_MAX_CURRENT, new SignedWordElement(812),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE, new SignedWordElement(813),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_VOLTAGE, new SignedWordElement(814),	SCALE_FACTOR_MINUS_1),
				// 		m(BatteryInverterSinexcel.ChannelId.BATTERY_VOLTAGE_PROTECTION_LIMIT,	new UnsignedWordElement(815), SCALE_FACTOR_MINUS_1)

				// ),

				// new FC3ReadRegistersTask(825, Priority.LOW,
				// 		m(BatteryInverterSinexcel.ChannelId.LEAKAGE_CURRENT_DC_COMPONENT_DETECTOR, new UnsignedWordElement(825)),
				// 		new DummyRegisterElement(826, 845),
				// 		m(BatteryInverterSinexcel.ChannelId.RESUME_AND_LIMIT_FREQUENCY, new SignedWordElement(846)),
				// 		m(BatteryInverterSinexcel.ChannelId.RESTORE_LOWER_FREQUENCY_OF_GRID_CONNECTION,	new SignedWordElement(847)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_REACTIVE_REFERENCE, new UnsignedWordElement(848)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V1,	new UnsignedWordElement(849)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V2,	new SignedWordElement(850)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V3,	new SignedWordElement(851)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V4,	new SignedWordElement(852)),

				// 		m(BatteryInverterSinexcel.ChannelId.MAX_CAPACITIVE_REACTIVE_REGULATION_Q1,	new SignedWordElement(853)),
				// 		m(BatteryInverterSinexcel.ChannelId.INITIAL_CAPACITIVE_REACTIVE_REGULATION_Q2,	new SignedWordElement(854)),
				// 		m(BatteryInverterSinexcel.ChannelId.INITIAL_INDUCTIVE_REACTIVE_REGULATION_Q3,	new SignedWordElement(855)),
				// 		m(BatteryInverterSinexcel.ChannelId.MAX_INDUCTIVE_REACTIVE_REGULATION_Q4,	new SignedWordElement(856)),

				// 		m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_RESPONSE_TIME,	new SignedWordElement(857)),
				// 		m(BatteryInverterSinexcel.ChannelId.REACTIVE_FIRST_ORDER_RESPONSE_TIME,	new SignedWordElement(858)), new DummyRegisterElement(859, 861),
				// 		m(BatteryInverterSinexcel.ChannelId.INITIAL_VOLTAGE_V_START, new SignedWordElement(862)),
				// 		m(BatteryInverterSinexcel.ChannelId.END_VOLTAGE_V_STOP, new SignedWordElement(863)),
				// 		m(BatteryInverterSinexcel.ChannelId.INITIAL_POWER_P_START, new SignedWordElement(864)),
				// 		m(BatteryInverterSinexcel.ChannelId.END_POWER_P_STOP, new SignedWordElement(865)),
				// 		m(BatteryInverterSinexcel.ChannelId.RETURN_TO_SERVICE_DELAY, new SignedWordElement(866)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLT_WATT_RESPONSE_TIME, new SignedWordElement(867)),
				// 		m(BatteryInverterSinexcel.ChannelId.START_OF_FREQUENY_DROP, new SignedWordElement(868)),
				// 		m(BatteryInverterSinexcel.ChannelId.SLOPE_OF_FREQUENCY_DROP, new SignedWordElement(869)),
				// 		m(BatteryInverterSinexcel.ChannelId.FREQUENCY_WATT_F_STOP_DISCHARGE,	new SignedWordElement(870)),
				// 		m(BatteryInverterSinexcel.ChannelId.FREQUENCY_WATT_F_STOP_CHARGE, new SignedWordElement(871)),
				// 		m(BatteryInverterSinexcel.ChannelId.VOLT_WATT_V_START_CHARGE, new SignedWordElement(872)),
				// 		new DummyRegisterElement(873, 875),
				// 		m(BatteryInverterSinexcel.ChannelId.SOFT_START_RAMP_RATE, new SignedWordElement(876)),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_RAMP_RATE, new SignedWordElement(877)),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_SETTING, new SignedWordElement(878),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P1, new SignedWordElement(879),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P2, new SignedWordElement(880),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P3, new SignedWordElement(881),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P4, new SignedWordElement(882),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P1, new SignedWordElement(883),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P2, new SignedWordElement(884),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P3, new SignedWordElement(885),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P4, new SignedWordElement(886),	SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.CONTINUOS_OVER_VOLTAGE_TRIP_THRESHOLD,	new SignedWordElement(887), SCALE_FACTOR_MINUS_2),
				// 		m(BatteryInverterSinexcel.ChannelId.FREQUENCY_VARIATION_RATE_TRIP_THRESHOLD,	new SignedWordElement(888)),
				// 		m(BatteryInverterSinexcel.ChannelId.PHASE_ANGLE_ABRUPT_TRIP_THRESHOLD,	new SignedWordElement(889)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_UPPER_LIMIT,	new SignedWordElement(890)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_LOWER_LIMIT,	new SignedWordElement(891)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_UPPER_LIMIT,	new SignedWordElement(892)),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_LOWER_LIMIT,	new SignedWordElement(893)),
				// 		m(BatteryInverterSinexcel.ChannelId.LOW_FREQUENCY_RAMP_RATE, new SignedWordElement(894))
				// ),

				// new FC3ReadRegistersTask(934, Priority.LOW,
				// 		m(BatteryInverterSinexcel.ChannelId.METER_ACTIVE_POWER, new SignedWordElement(934),	SCALE_FACTOR_1),
				// 		new DummyRegisterElement(935, 947),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(948),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(949),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(950),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L1,	new UnsignedWordElement(951), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L2,	new UnsignedWordElement(952), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L3,	new UnsignedWordElement(953), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_1,	new UnsignedWordElement(954), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_1,	new UnsignedWordElement(955), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_1,	new UnsignedWordElement(956), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_2,	new UnsignedWordElement(957), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_2,	new UnsignedWordElement(958), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_2,	new UnsignedWordElement(959), SCALE_FACTOR_MINUS_3),
  			// 		m(BatteryInverterSinexcel.ChannelIdOUTPUT_CURRENT_CALIBRATION_L1, new UnsignedWordElement(960),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L2, new UnsignedWordElement(961),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L3, new UnsignedWordElement(962),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.POSITIVE_BUS_VOLTAGE_CALIBRATION,	new UnsignedWordElement(963), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.NEGATIVE_BUS_VOLTAGE_CALIBRATION,	new UnsignedWordElement(964), SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_CALIBRATION, new UnsignedWordElement(965),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.DC_CURRENT_CALIBRATION, new UnsignedWordElement(966),	SCALE_FACTOR_MINUS_3),
				// 		m(BatteryInverterSinexcel.ChannelId.DC_INDUCTOR_CURRENT_CALIBRATION,	new UnsignedWordElement(967), SCALE_FACTOR_MINUS_3)
				// ),

        /*******************************************************************************************************************/
        // WRITE registers

        new FC6WriteRegisterTask(1001, m(BatteryInverterSinexcel.ChannelId.START_INVERTER, new UnsignedWordElement(1001))), /* 650 */
				new FC6WriteRegisterTask(1002, m(BatteryInverterSinexcel.ChannelId.STOP_INVERTER, new UnsignedWordElement(1002))), /* 651 */
				new FC6WriteRegisterTask(1003, m(BatteryInverterSinexcel.ChannelId.CLEAR_FAILURE, new UnsignedWordElement(1003))), /* 652 */
        
        new FC6WriteRegisterTask(1006, m(BatteryInverterSinexcel.ChannelId.SET_GRID_MODE, new UnsignedWordElement(1006))), /* 653 */

				new FC6WriteRegisterTask(1022, m(BatteryInverterSinexcel.ChannelId.ACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(1022))), /* 794 */
				new FC6WriteRegisterTask(1023, m(BatteryInverterSinexcel.ChannelId.REACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(1023))), /* 792 */

				new FC6WriteRegisterTask(1024, m(BatteryInverterSinexcel.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(1024), SCALE_FACTOR_2_AND_INVERT)), /* 135 */
				new FC6WriteRegisterTask(1025, m(BatteryInverterSinexcel.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(1025),	SCALE_FACTOR_2_AND_INVERT)), /*136 */

        new FC6WriteRegisterTask(1215, m(BatteryInverterSinexcel.ChannelId.BMS_PROTOCOL_SELECTION, new UnsignedWordElement(1215))), /* 330 */
				new FC6WriteRegisterTask(1216, m(BatteryInverterSinexcel.ChannelId.START_UP_MODE, new UnsignedWordElement(1216))) /* 805 */

        /*******************************************************************************************************************/
				// new FC6WriteRegisterTask(137, m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_VOLTAGE, new SignedWordElement(137),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(138, m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_FREQUENCY, new SignedWordElement(138),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(300, m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_1, new UnsignedWordElement(300))),
				// new FC6WriteRegisterTask(301, m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_2, new UnsignedWordElement(301))),
				// new FC6WriteRegisterTask(302, m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_3, new UnsignedWordElement(302))),
				// new FC6WriteRegisterTask(303, m(BatteryInverterSinexcel.ChannelId.IP_ADDRESS_BLOCK_4, new UnsignedWordElement(303))),
				// new FC6WriteRegisterTask(304, m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_1, new UnsignedWordElement(304))),
				// new FC6WriteRegisterTask(305, m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_2, new UnsignedWordElement(305))),
				// new FC6WriteRegisterTask(306, m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_3, new UnsignedWordElement(306))),
				// new FC6WriteRegisterTask(307, m(BatteryInverterSinexcel.ChannelId.NETMASK_BLOCK_4, new UnsignedWordElement(307))),
				// new FC6WriteRegisterTask(308, m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_1, new UnsignedWordElement(308))),
				// new FC6WriteRegisterTask(309, m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_2, new UnsignedWordElement(309))),
				// new FC6WriteRegisterTask(310, m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_3, new UnsignedWordElement(310))),
				// new FC6WriteRegisterTask(311, m(BatteryInverterSinexcel.ChannelId.GATEWAY_IP_BLOCK_4, new UnsignedWordElement(311))),
				// new FC6WriteRegisterTask(312, m(BatteryInverterSinexcel.ChannelId.MAC, new UnsignedWordElement(312))),
				// new FC6WriteRegisterTask(316, m(BatteryInverterSinexcel.ChannelId.MODBUS_UNIT_ID, new UnsignedWordElement(316))),
				// new FC6WriteRegisterTask(320, m(BatteryInverterSinexcel.ChannelId.BAUDRATE, new UnsignedWordElement(320))),
				// new FC6WriteRegisterTask(325, m(BatteryInverterSinexcel.ChannelId.INTERFACE_TYPE, new UnsignedWordElement(325))),
				// new FC6WriteRegisterTask(326, m(BatteryInverterSinexcel.ChannelId.COMMUNICATION_PROTOCOL_SELECTION,	new UnsignedWordElement(326))),
				// new FC6WriteRegisterTask(327, m(BatteryInverterSinexcel.ChannelId.EMS_TIMEOUT, new UnsignedWordElement(327))),
				// new FC6WriteRegisterTask(328, m(BatteryInverterSinexcel.ChannelId.EPO_ENABLE, new UnsignedWordElement(328))),
				// new FC6WriteRegisterTask(329, m(BatteryInverterSinexcel.ChannelId.BMS_TIMEOUT, new UnsignedWordElement(329))),
				// new FC6WriteRegisterTask(332, m(BatteryInverterSinexcel.ChannelId.BUZZER_ENABLE, new UnsignedWordElement(332))),
				// new FC6WriteRegisterTask(333, m(BatteryInverterSinexcel.ChannelId.RESTORE_FACTORY_SETTING, new UnsignedWordElement(333))),
				// new FC6WriteRegisterTask(653, m(BatteryInverterSinexcel.ChannelId.SET_ON_GRID_MODE, new UnsignedWordElement(653))),
				// new FC6WriteRegisterTask(654, m(BatteryInverterSinexcel.ChannelId.SET_OFF_GRID_MODE, new UnsignedWordElement(654))),
				// new FC6WriteRegisterTask(655, m(BatteryInverterSinexcel.ChannelId.SET_STANDBY_COMMAND, new UnsignedWordElement(655))),
				// new FC6WriteRegisterTask(656, m(BatteryInverterSinexcel.ChannelId.SET_SOFT_START, new UnsignedWordElement(656))),
				// new FC6WriteRegisterTask(657, m(BatteryInverterSinexcel.ChannelId.RESET_INSTRUCTION, new UnsignedWordElement(657))),
				// new FC6WriteRegisterTask(658, m(BatteryInverterSinexcel.ChannelId.GRID_STOP, new UnsignedWordElement(658))),
				// new FC6WriteRegisterTask(752, m(BatteryInverterSinexcel.ChannelId.SWITCHING_DEVICE_ACCESS_SETTING,	new UnsignedWordElement(752))),
				// new FC6WriteRegisterTask(755, m(BatteryInverterSinexcel.ChannelId.CPU_TYPE, new UnsignedWordElement(755))),
				// new FC6WriteRegisterTask(756, m(BatteryInverterSinexcel.ChannelId.OFF_GRID_AND_PARALLEL_ENABLE,	new UnsignedWordElement(756))),
				// new FC6WriteRegisterTask(757, m(BatteryInverterSinexcel.ChannelId.SET_DC_SOFT_START_EXTERNAL_CONTROL,	new UnsignedWordElement(757))),
				// new FC6WriteRegisterTask(768, m(BatteryInverterSinexcel.ChannelId.GRID_OVER_VOLTAGE_PROTECTION_AMPLITUDE,	new SignedWordElement(768))),
				// new FC6WriteRegisterTask(769, m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(769))),
				// new FC6WriteRegisterTask(770, m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(770))),
				// new FC6WriteRegisterTask(771, m(BatteryInverterSinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(771))),
				// new FC6WriteRegisterTask(772, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_1, new SignedWordElement(772))),
				// new FC6WriteRegisterTask(773, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(773))),
				// new FC6WriteRegisterTask(774, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(774))),
				// new FC6WriteRegisterTask(775, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(775))),
				// new FC6WriteRegisterTask(776, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_3, new SignedWordElement(776))),
				// new FC6WriteRegisterTask(777, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_3, new SignedWordElement(777))),
				// new FC6WriteRegisterTask(778, m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_1,	new SignedWordElement(778))),
				// new FC6WriteRegisterTask(779, m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(779))),
				// new FC6WriteRegisterTask(780, m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_2,	new SignedWordElement(780))),
				// new FC6WriteRegisterTask(781, m(BatteryInverterSinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(781))),
				// new FC6WriteRegisterTask(782, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_1,	new SignedWordElement(782))),
				// new FC6WriteRegisterTask(783, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_1,	new SignedWordElement(783))),
				// new FC6WriteRegisterTask(784, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_2,	new SignedWordElement(784))),
				// new FC6WriteRegisterTask(785, m(BatteryInverterSinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_2,	new SignedWordElement(785))),
				// new FC6WriteRegisterTask(786, m(BatteryInverterSinexcel.ChannelId.RECONNECT_TIME, new SignedWordElement(786))),
				// new FC6WriteRegisterTask(790, m(BatteryInverterSinexcel.ChannelId.ANTI_ISLANDING, new UnsignedWordElement(790))),
				// new FC6WriteRegisterTask(791, m(BatteryInverterSinexcel.ChannelId.FREQUENCY_VOLTAGE_RIDE_THROUGH,	new UnsignedWordElement(791))),
				// new FC6WriteRegisterTask(793, m(BatteryInverterSinexcel.ChannelId.POWER_RISING_MODE, new UnsignedWordElement(793))),

        // new FC6WriteRegisterTask(795, m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_ASYMMETRIC_DETECTON,	new UnsignedWordElement(795))),
				// new FC6WriteRegisterTask(796, m(BatteryInverterSinexcel.ChannelId.CONTINUOUS_OVERVOLTAGE_DETECTION,	new UnsignedWordElement(796))),
				// new FC6WriteRegisterTask(797, m(BatteryInverterSinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON, new UnsignedWordElement(797))),
				// new FC6WriteRegisterTask(798, m(BatteryInverterSinexcel.ChannelId.NEUTRAL_FLOATING_DETECTION, new UnsignedWordElement(798))),
				// new FC6WriteRegisterTask(799, m(BatteryInverterSinexcel.ChannelId.OFF_GRID_BLACKSTART_MODE, new UnsignedWordElement(799))),
				// new FC6WriteRegisterTask(800, m(BatteryInverterSinexcel.ChannelId.GRID_CODE_SELECTION, new UnsignedWordElement(800))),
				// new FC6WriteRegisterTask(801, m(BatteryInverterSinexcel.ChannelId.GRID_CONNECTED_ACTIVE_CAPACITY_LIMITATION_FUNCTION,	new UnsignedWordElement(801))),
				// new FC6WriteRegisterTask(802, m(BatteryInverterSinexcel.ChannelId.GRID_ACTIVE_POWER_CAPACITY_SETTING,	new UnsignedWordElement(802))),
				// new FC6WriteRegisterTask(803, m(BatteryInverterSinexcel.ChannelId.SINGLE_PHASE_MODE_SELECTION, new UnsignedWordElement(803))),
				// new FC6WriteRegisterTask(804, m(BatteryInverterSinexcel.ChannelId.OVER_VOLTAGE_DROP_ACTIVE, new UnsignedWordElement(804))),
				// new FC6WriteRegisterTask(807, m(BatteryInverterSinexcel.ChannelId.LOCAL_ID_SETTING, new SignedWordElement(807))),
				// new FC6WriteRegisterTask(808, m(BatteryInverterSinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE, new SignedWordElement(808),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(809, m(BatteryInverterSinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE, new SignedWordElement(809),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(810, m(BatteryInverterSinexcel.ChannelId.CURRENT_FROM_TOPPING_CHARGING_TO_FLOAT_CHARGING,	new SignedWordElement(810), SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(811, m(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_CURRENT, new SignedWordElement(811),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(812, m(BatteryInverterSinexcel.ChannelId.DISCHARGE_MAX_CURRENT, new SignedWordElement(812),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(813, m(BatteryInverterSinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE, new SignedWordElement(813),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(814, m(BatteryInverterSinexcel.ChannelId.CHARGE_MAX_VOLTAGE, new SignedWordElement(814),	SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(815, m(BatteryInverterSinexcel.ChannelId.BATTERY_VOLTAGE_PROTECTION_LIMIT,	new UnsignedWordElement(815), SCALE_FACTOR_MINUS_1)),
				// new FC6WriteRegisterTask(825, m(BatteryInverterSinexcel.ChannelId.LEAKAGE_CURRENT_DC_COMPONENT_DETECTOR,	new UnsignedWordElement(825))),
				// // TODO Check scale factors, channel names and so on...
				// new FC6WriteRegisterTask(846, m(BatteryInverterSinexcel.ChannelId.RESUME_AND_LIMIT_FREQUENCY, new SignedWordElement(846))),
				// new FC6WriteRegisterTask(847, m(BatteryInverterSinexcel.ChannelId.RESTORE_LOWER_FREQUENCY_OF_GRID_CONNECTION,	new SignedWordElement(847))),
				// new FC6WriteRegisterTask(848, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_REACTIVE_REFERENCE, new SignedWordElement(848))),
				// new FC6WriteRegisterTask(849, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V1,	new SignedWordElement(849))),
				// new FC6WriteRegisterTask(850, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V2,	new SignedWordElement(850))),
				// new FC6WriteRegisterTask(851, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V3,	new SignedWordElement(851))),
				// new FC6WriteRegisterTask(852, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V4,	new SignedWordElement(852))),
				// new FC6WriteRegisterTask(853, m(BatteryInverterSinexcel.ChannelId.MAX_CAPACITIVE_REACTIVE_REGULATION_Q1,	new SignedWordElement(853))),
				// new FC6WriteRegisterTask(854, m(BatteryInverterSinexcel.ChannelId.INITIAL_CAPACITIVE_REACTIVE_REGULATION_Q2,	new SignedWordElement(854))),
				// new FC6WriteRegisterTask(855, m(BatteryInverterSinexcel.ChannelId.INITIAL_INDUCTIVE_REACTIVE_REGULATION_Q3,	new SignedWordElement(855))),
				// new FC6WriteRegisterTask(856, m(BatteryInverterSinexcel.ChannelId.MAX_INDUCTIVE_REACTIVE_REGULATION_Q4,	new SignedWordElement(856))),
				// new FC6WriteRegisterTask(857, m(BatteryInverterSinexcel.ChannelId.VOLTAGE_AND_REACTIVE_RESPONSE_TIME,	new SignedWordElement(857))),
				// new FC6WriteRegisterTask(858, m(BatteryInverterSinexcel.ChannelId.REACTIVE_FIRST_ORDER_RESPONSE_TIME,	new SignedWordElement(858))),
				// new FC6WriteRegisterTask(862, m(BatteryInverterSinexcel.ChannelId.INITIAL_VOLTAGE_V_START, new SignedWordElement(862))),
				// new FC6WriteRegisterTask(863, m(BatteryInverterSinexcel.ChannelId.END_VOLTAGE_V_STOP, new SignedWordElement(863))),
				// new FC6WriteRegisterTask(864, m(BatteryInverterSinexcel.ChannelId.INITIAL_POWER_P_START, new SignedWordElement(864))),
				// new FC6WriteRegisterTask(865, m(BatteryInverterSinexcel.ChannelId.END_POWER_P_STOP, new SignedWordElement(865))),
				// new FC6WriteRegisterTask(866, m(BatteryInverterSinexcel.ChannelId.RETURN_TO_SERVICE_DELAY, new SignedWordElement(866))),
				// new FC6WriteRegisterTask(867, m(BatteryInverterSinexcel.ChannelId.VOLT_WATT_RESPONSE_TIME, new SignedWordElement(867))),
				// new FC6WriteRegisterTask(868, m(BatteryInverterSinexcel.ChannelId.START_OF_FREQUENY_DROP, new SignedWordElement(868))),
				// new FC6WriteRegisterTask(869, m(BatteryInverterSinexcel.ChannelId.SLOPE_OF_FREQUENCY_DROP, new SignedWordElement(869))),
				// new FC6WriteRegisterTask(870, m(BatteryInverterSinexcel.ChannelId.FREQUENCY_WATT_F_STOP_DISCHARGE,	new SignedWordElement(870))),
				// new FC6WriteRegisterTask(871, m(BatteryInverterSinexcel.ChannelId.FREQUENCY_WATT_F_STOP_CHARGE, new SignedWordElement(871))),
				// new FC6WriteRegisterTask(872, m(BatteryInverterSinexcel.ChannelId.VOLT_WATT_V_START_CHARGE, new SignedWordElement(872))),
				// new FC6WriteRegisterTask(876, m(BatteryInverterSinexcel.ChannelId.SOFT_START_RAMP_RATE, new SignedWordElement(876))),
				// new FC6WriteRegisterTask(877, m(BatteryInverterSinexcel.ChannelId.POWER_RAMP_RATE, new SignedWordElement(877))),
				// new FC6WriteRegisterTask(878, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_SETTING, new SignedWordElement(878))),
				// new FC6WriteRegisterTask(879, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P1, new SignedWordElement(879))),
				// new FC6WriteRegisterTask(880, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P2, new SignedWordElement(880))),
				// new FC6WriteRegisterTask(881, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P3, new SignedWordElement(881))),
				// new FC6WriteRegisterTask(882, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_P4, new SignedWordElement(882))),
				// new FC6WriteRegisterTask(883, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P1, new SignedWordElement(883))),
				// new FC6WriteRegisterTask(884, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P2, new SignedWordElement(884))),
				// new FC6WriteRegisterTask(885, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P3, new SignedWordElement(885))),
				// new FC6WriteRegisterTask(886, m(BatteryInverterSinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P4, new SignedWordElement(886))),
				// new FC6WriteRegisterTask(887, m(BatteryInverterSinexcel.ChannelId.CONTINUOS_OVER_VOLTAGE_TRIP_THRESHOLD,	new SignedWordElement(887))),
				// new FC6WriteRegisterTask(888, m(BatteryInverterSinexcel.ChannelId.FREQUENCY_VARIATION_RATE_TRIP_THRESHOLD,	new SignedWordElement(888))),
				// new FC6WriteRegisterTask(889, m(BatteryInverterSinexcel.ChannelId.PHASE_ANGLE_ABRUPT_TRIP_THRESHOLD,	new SignedWordElement(889))),
				// new FC6WriteRegisterTask(890, m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_UPPER_LIMIT,	new SignedWordElement(890))),
				// new FC6WriteRegisterTask(891, m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_LOWER_LIMIT,	new SignedWordElement(891))),
				// new FC6WriteRegisterTask(892, m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_UPPER_LIMIT,	new SignedWordElement(892))),
				// new FC6WriteRegisterTask(893, m(BatteryInverterSinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_LOWER_LIMIT,	new SignedWordElement(893))),
				// new FC6WriteRegisterTask(894, m(BatteryInverterSinexcel.ChannelId.LOW_FREQUENCY_RAMP_RATE, new SignedWordElement(894))),
				// new FC6WriteRegisterTask(934, m(BatteryInverterSinexcel.ChannelId.METER_ACTIVE_POWER, new SignedWordElement(934))),
				// new FC6WriteRegisterTask(948, m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(948), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(949, m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(949), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(950, m(BatteryInverterSinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(950), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(951, m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L1,	new UnsignedWordElement(951), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(952, m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L2,	new UnsignedWordElement(952), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(953, m(BatteryInverterSinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L3,	new UnsignedWordElement(953), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(954, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_1,	new UnsignedWordElement(954), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(955, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_1,	new UnsignedWordElement(955), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(956, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_1,	new UnsignedWordElement(956), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(957, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_2,	new UnsignedWordElement(957), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(958, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_2,	new UnsignedWordElement(958), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(959, m(BatteryInverterSinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_2,	new UnsignedWordElement(959), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(960, m(BatteryInverterSinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L1, new UnsignedWordElement(960),	SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(961, m(BatteryInverterSinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L2, new UnsignedWordElement(961),	SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(962, m(BatteryInverterSinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L3, new UnsignedWordElement(962),	SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(963, m(BatteryInverterSinexcel.ChannelId.POSITIVE_BUS_VOLTAGE_CALIBRATION,	new UnsignedWordElement(963), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(964, m(BatteryInverterSinexcel.ChannelId.NEGATIVE_BUS_VOLTAGE_CALIBRATION,	new UnsignedWordElement(964), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(965, m(BatteryInverterSinexcel.ChannelId.DC_VOLTAGE_CALIBRATION, new UnsignedWordElement(965),	SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(966, m(BatteryInverterSinexcel.ChannelId.DC_CURRENT_CALIBRATION, new UnsignedWordElement(966),	SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(967, m(BatteryInverterSinexcel.ChannelId.DC_INDUCTOR_CURRENT_CALIBRATION,	new UnsignedWordElement(967), SCALE_FACTOR_MINUS_3)),
				// new FC6WriteRegisterTask(4001, m(BatteryInverterSinexcel.ChannelId.TIME_SETTING, new SignedWordElement(4001))),
				// new FC6WriteRegisterTask(4007, m(BatteryInverterSinexcel.ChannelId.PASSWORD, new SignedWordElement(4007)))

		); 
	}

	/**
	 * The Sinexcel Battery Inverter claims to outputting a little bit of power even
	 * if it does not. This little filter ignores values for ActivePower less than
	 * 100 (charge/discharge).
	 */
//	private static final ElementToChannelConverter IGNORE_LESS_THAN_100 = new ElementToChannelConverter(//
//			obj -> {
//				if (obj == null) {
//					return null;
//				}
//				int value = (Short) obj;
//				if (Math.abs(value) < 100) {
//					return 0;
//				}
//				return value;
//			}, //
//			value -> value);

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		// Calculate Energy
		var activePower = this.getActivePower().get();
		if (activePower == null) {
			// Not available
			this.calculateChargeEnergy.update(null);
			this.calculateDischargeEnergy.update(null);
		} else if (activePower > 0) {
			// Buy-From-Grid
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(activePower);
		} else {
			// Sell-To-Grid
			this.calculateChargeEnergy.update(activePower * -1);
			this.calculateDischargeEnergy.update(0);
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
