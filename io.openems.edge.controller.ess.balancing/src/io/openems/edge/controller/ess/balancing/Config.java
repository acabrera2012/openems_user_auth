package io.openems.edge.controller.ess.balancing;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Balancing", //
		description = "Optimizes the self-consumption by keeping the grid meter on zero.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlBalancing0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Mode", description = "Set the type of mode.")
	Mode mode() default Mode.MANUAL_ON;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id();

	@AttributeDefinition(name = "Grid-Meter-ID", description = "ID of the Grid-Meter.")
	String meter_id();

	@AttributeDefinition(name = "Target Grid Setpoint", description = "The target setpoint for grid. Positive for buy-from-grid; negative for sell-to-grid.")
	int targetGridSetpoint() default 0;

	@AttributeDefinition(name = "Ess target filter", description = "This is auto-generated by 'Ess-ID'.")
	String ess_target() default "(enabled=true)";

	@AttributeDefinition(name = "Meter target filter", description = "This is auto-generated by 'Meter-ID'.")
	String meter_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Controller Ess Balancing [{id}]";

}
