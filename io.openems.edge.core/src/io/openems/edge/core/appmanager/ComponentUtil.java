package io.openems.edge.core.appmanager;

import java.util.List;
import java.util.Optional;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.EdgeConfig;
import io.openems.common.types.EdgeConfig.Component;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.cycle.Cycle;
import io.openems.edge.common.host.Host;
import io.openems.edge.common.meta.Meta;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.user.User;
import io.openems.edge.core.host.NetworkInterface;
import io.openems.edge.io.api.DigitalOutput;
import io.openems.edge.predictor.api.manager.PredictorManager;

public interface ComponentUtil {

	public static final List<String> CORE_COMPONENT_IDS = List.of(//
			AppManager.SINGLETON_COMPONENT_ID, //
			ComponentManager.SINGLETON_COMPONENT_ID, //
			Cycle.SINGLETON_COMPONENT_ID, //
			Host.SINGLETON_COMPONENT_ID, //
			Meta.SINGLETON_COMPONENT_ID, //
			PredictorManager.SINGLETON_COMPONENT_ID, //
			Sum.SINGLETON_COMPONENT_ID //
	);

	public record RelayInfo(//
			String id, //
			String alias, //
			List<RelayContactInfo> channels //
	) {

		/**
		 * Returns the alias if not blank otherwise return the id.
		 * 
		 * @return the string to display
		 */
		public String getDisplayName() {
			return this.alias().isBlank() ? this.id() : this.alias();
		}

	}

	public record RelayContactInfo(//
			String channel, //
			String alias, //
			List<OpenemsComponent> usingComponents //
	) {

		/**
		 * Returns the alias if not blank otherwise return the id.
		 * 
		 * @return the string to display
		 */
		public String getDisplayName() {
			return this.alias().isBlank() ? this.channel() : this.alias();
		}

	}

	public record PreferredRelay(//
			int numberOfRelays, //
			/**
			 * Indices of the relay contacts.
			 */
			int[] preferredRelays //
	) {

	}

	/**
	 * Gets the interfaces of the currently active network settings.
	 *
	 * @return all interfaces in the host configuration
	 * @throws OpenemsNamedException on error
	 */
	public List<NetworkInterface<?>> getInterfaces() throws OpenemsNamedException;

	/**
	 * Checks if any component has the given String in its configuration.
	 *
	 * @param value     that no component should have
	 * @param ignoreIds the id s of components which configuration should be ignored
	 * @return true if a component has the given String in its configuration
	 */
	public boolean anyComponentUses(String value, List<String> ignoreIds);

	/**
	 * Gets all {@link RelayInfo RelayInfos} of all {@link DigitalOutput
	 * DigitalOutputs}.
	 * 
	 * @param ignoreIds the Component-IDs that should be ignored to check if they
	 *                  use any relay
	 * @return a list of {@link RelayInfo RelayInfos}
	 */
	public List<RelayInfo> getAllRelayInfos(List<String> ignoreIds);

	/**
	 * Gets all {@link RelayInfo RelayInfos} of all {@link DigitalOutput
	 * DigitalOutputs}.
	 * 
	 * @return a list of {@link RelayInfo RelayInfos}
	 * @implNote calls {@link ComponentUtil#getAllRelayInfos(List)} with
	 *           {@link ComponentUtil#CORE_COMPONENT_IDS} to ignore.
	 */
	public default List<RelayInfo> getAllRelayInfos() {
		return this.getAllRelayInfos(CORE_COMPONENT_IDS);
	}

	/**
	 * Gets a list of the current relays where every relay has atleast one available
	 * relay contact.
	 * 
	 * @param ignoreIds the id s of components which configuration should be ignored
	 * @return a list of the {@link RelayInfo RelayInfos}
	 */
	public default List<RelayInfo> getAvailableRelayInfos(List<String> ignoreIds) {
		return this.getAllRelayInfos(ignoreIds).stream() //
				.map(ri -> new RelayInfo(ri.id(), ri.alias(), ri.channels().stream()//
						.filter(ci -> ci.usingComponents().isEmpty())//
						.toList())) //
				.filter(ri -> !ri.channels().isEmpty()) //
				.toList();
	}

	/**
	 * Gets a list of the current relays where every relay has atleast one available
	 * relay contact.
	 * 
	 * @return a list of the {@link RelayInfo RelayInfos}
	 */
	public default List<RelayInfo> getAvailableRelayInfos() {
		return this.getAvailableRelayInfos(CORE_COMPONENT_IDS);
	}

	/**
	 * Gets a list of current available relay contacts of a relay.
	 *
	 * @param ignoreIds the id s of components which configuration should be ignored
	 * @param ioName    the id of the id
	 * @return a list of the available {@link RelayContactInfo}
	 */
	public default List<RelayContactInfo> getAvailableRelayContactInfos(List<String> ignoreIds, String ioName) {
		return this.getAvailableRelayInfos(ignoreIds).stream() //
				.filter(ri -> ri.id().equals(ioName)) //
				.flatMap(ri -> ri.channels().stream()) //
				.toList();
	}

	/**
	 * Gets a list of current available relay contacts of a relay.
	 *
	 * @param ioName the id of the id
	 * @return a list of the available {@link RelayContactInfo}
	 */
	public default List<RelayContactInfo> getAvailableRelayContactInfos(String ioName) {
		return this.getAvailableRelayContactInfos(CORE_COMPONENT_IDS, ioName);
	}

	/**
	 * Gets a list of current available relay contacts of all found relays.
	 *
	 * @param ignoreIds the id s of components which configuration should be ignored
	 * @return a list of the available {@link RelayContactInfo}
	 */
	public default List<RelayContactInfo> getAvailableRelayContactInfos(List<String> ignoreIds) {
		return this.getAvailableRelayInfos(ignoreIds).stream() //
				.flatMap(ri -> ri.channels().stream()) //
				.toList();
	}

	/**
	 * Gets a list of current available relay contacts of all found relays.
	 *
	 * @return a list of the available {@link RelayContactInfo}
	 */
	public default List<RelayContactInfo> getAvailableRelayContactInfos() {
		return this.getAvailableRelayContactInfos(CORE_COMPONENT_IDS);
	}

	/**
	 * Searches a component with the given component configuration.
	 *
	 * @param component with the configuration to be searched for
	 * @return the found component or null if not found
	 */
	public Component getComponentByConfig(Component component);

	/**
	 * Gets the enabled Components with the starting id.
	 *
	 * @param <T> the type to which the components should be assignable to
	 * @param id  the starting id of the components
	 * @return a list of found components
	 */
	public <T extends OpenemsComponent> List<T> getEnabledComponentsOfStartingId(String id);

	/**
	 * Gets the enabled Components of a Type.
	 *
	 * <p>
	 * the method 'this.componentManager.getEnabledComponentsOfType(clazz)' does not
	 * return the component if the given class is an interface and the component has
	 * the interface implemented
	 *
	 * @param <T>   the type to which the components should be assignable to
	 * @param clazz to which the component should be assignable to
	 * @return a list of found components
	 */
	public <T extends OpenemsComponent> List<T> getEnabledComponentsOfType(Class<T> clazz);

	/**
	 * Gets the next available id with the baseName.
	 *
	 * @param baseName     like ess, meter without a number
	 * @param componentIds the used componentIds from the other apps, because if the
	 *                     user updates multiple instances very quickly and
	 *                     components of the same type are created they are not
	 *                     instantly added to the componentManager
	 * @return the id
	 */
	public default String getNextAvailableId(String baseName, List<String> componentIds) {
		return this.getNextAvailableId(baseName, 0, componentIds);
	}

	/**
	 * Gets the next available id with the baseName starting with the given
	 * startingNumber.
	 *
	 * @param baseName       like ess, meter without a number
	 * @param startingNumber the number at the end of the id to start from
	 * @param componentIds   the used componentIds from the other apps, because if
	 *                       the user updates multiple instances very quickly and
	 *                       components of the same type are created they are not
	 *                       instantly added to the componentManager
	 * @return the id
	 */
	public String getNextAvailableId(String baseName, int startingNumber, List<String> componentIds);

	/**
	 * Gets the preferred relays. If the default ports are already taken the next
	 * available in a row are taken. If not enough in a row are available the first
	 * available relays of any relay are returned.
	 * 
	 * @param ignoreIds            the ids of the components that should be ignored
	 * @param cnt                  the number of the result number of relays
	 * @param first                the first {@link PreferredRelay} to ensure
	 *                             at least one
	 * @param inputPreferredRelays the other {@link PreferredRelay} options
	 * @return the first found preferred relays
	 */
	public String[] getPreferredRelays(//
			List<String> ignoreIds, //
			int cnt, //
			PreferredRelay first, //
			PreferredRelay... inputPreferredRelays //
	);

	/**
	 * Gets the preferred relays. If the default ports are are already taken the
	 * next available in a row are taken. If not enough in a row are available the
	 * first available relays of any relay are returned.
	 * 
	 * @param cnt                  the number of the result number of relays
	 * @param first                the first {@link PreferredRelay} to ensure at
	 *                             least one
	 * @param inputPreferredRelays the other {@link PreferredRelay} options
	 * @return the first found preferred relays
	 * @implNote calls
	 *           {@link ComponentUtil#getPreferredRelays(List, int, PreferredRelay, PreferredRelay...)}
	 *           with {@link ComponentUtil#CORE_COMPONENT_IDS} to ignore.
	 */
	public default String[] getPreferredRelays(//
			int cnt, //
			PreferredRelay first, //
			PreferredRelay... inputPreferredRelays //
	) {
		return this.getPreferredRelays(CORE_COMPONENT_IDS, cnt, first, inputPreferredRelays);
	}

	/**
	 * updates the interfaces in the Host configuration.
	 *
	 * @param user       the executing user
	 * @param interfaces the new interfaces
	 * @throws OpenemsNamedException on error
	 */
	public void updateInterfaces(User user, List<NetworkInterface<?>> interfaces) throws OpenemsNamedException;

	/**
	 * updates the execution order of the scheduler only adds or changes order of
	 * the given id s.
	 *
	 * @param user                    the executing user
	 * @param schedulerExecutionOrder the execution order
	 * @param components              the components which are currently created
	 * @throws OpenemsNamedException when the scheduler can not be updated
	 */
	public void updateScheduler(User user, List<String> schedulerExecutionOrder, List<EdgeConfig.Component> components)
			throws OpenemsNamedException;

	/**
	 * removes the given id s from the scheduler if they exist in the scheduler.
	 *
	 * @param user       the executing user
	 * @param removedIds the ip s that should be removed
	 * @throws OpenemsNamedException on error
	 */
	public void removeIdsInSchedulerIfExisting(User user, List<String> removedIds) throws OpenemsNamedException;

	/**
	 * Gets the current id's in the scheduler.
	 *
	 * @return the id's
	 * @throws OpenemsNamedException on error
	 */
	public List<String> getSchedulerIds() throws OpenemsNamedException;

	/**
	 * Gets the scheduler Component.
	 *
	 * @return the scheduler component
	 * @throws OpenemsNamedException if more or no scheduler is available
	 */
	public Component getScheduler() throws OpenemsNamedException;

	/**
	 * Creates a new List with only components which exist in the current
	 * configuration or in the passed components list.
	 *
	 * @param ids        the initial list
	 * @param components the current creating components
	 * @return a new list only with id's of components that exist
	 */
	public List<String> removeIdsWhichNotExist(List<String> ids, List<EdgeConfig.Component> components);

	/**
	 * Inserts the insertOrder into the actual Order.
	 *
	 * @param actualOrder the current scheduler order
	 * @param insertOrder the order which should be inserted
	 * @return the complete order
	 */
	public List<String> insertSchedulerOrder(List<String> actualOrder, List<String> insertOrder);

	/**
	 * updates the host configuration deletes ip s that are in {@link oldIps} but
	 * not in {@link ips} and adds ip s that are in {@link ips} but not in
	 * {@link oldIps}.
	 *
	 * @param user   the executing user
	 * @param ips    the ip s that should be in the configuration
	 * @param oldIps the old ip s that were in the old configuration
	 * @throws OpenemsNamedException on error
	 */
	public void updateHosts(User user, List<InterfaceConfiguration> ips, List<InterfaceConfiguration> oldIps)
			throws OpenemsNamedException;

	/**
	 * Gets an {@link Optional} of an {@link EdgeConfig.Component}.
	 *
	 * @param id        the id of the component
	 * @param factoryId the factoryId of the component
	 * @return the optional component
	 */
	public Optional<EdgeConfig.Component> getComponent(String id, String factoryId);

	/**
	 * Gets an array of modbus unit ids which are already used by other components.
	 * The result of this method may not contain all modbus unit ids.
	 * 
	 * @param modbusComponent the id of the modbus component
	 * @return an array of used modbus unit ids
	 */
	public int[] getUsedModbusUnitIds(String modbusComponent);

}
