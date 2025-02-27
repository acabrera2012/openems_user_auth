package io.openems.backend.uiwebsocket.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;

import com.google.common.collect.TreeBasedTable;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import io.openems.backend.common.component.AbstractOpenemsBackendComponent;
import io.openems.backend.common.edgewebsocket.EdgeCache;
import io.openems.backend.common.edgewebsocket.EdgeWebsocket;
import io.openems.backend.common.jsonrpc.JsonRpcRequestHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.User;
import io.openems.backend.common.timedata.TimedataManager;
import io.openems.backend.common.uiwebsocket.UiWebsocket;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.AbstractJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcNotification;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;
import io.openems.common.jsonrpc.notification.TimestampedDataNotification;
import io.openems.common.utils.ThreadPoolUtils;
import io.openems.common.websocket.AbstractWebsocketServer.DebugMode;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = "Ui.Websocket", //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		immediate = true //
)
@EventTopics({ //
		Metadata.Events.AFTER_IS_INITIALIZED //
})
public class UiWebsocketImpl extends AbstractOpenemsBackendComponent implements UiWebsocket, EventHandler {

	private static final String EDGE_ID = "backend0";
	private static final String COMPONENT_ID = "uiwebsocket";

	private final ScheduledExecutorService debugLogExecutor = Executors.newSingleThreadScheduledExecutor();

	protected WebsocketServer server = null;

	@Reference
	protected volatile JsonRpcRequestHandler jsonRpcRequestHandler;

	@Reference
	protected volatile Metadata metadata;

	@Reference
	protected volatile EdgeWebsocket edgeWebsocket;

	@Reference
	protected volatile TimedataManager timedataManager;

	public UiWebsocketImpl() {
		super("Ui.Websocket");
	}

	private Config config;

	@Activate
	private void activate(Config config) {
		this.config = config;
		this.debugLogExecutor.scheduleWithFixedDelay(() -> {
			var data = TreeBasedTable.<Long, String, JsonElement>create();
			var now = Instant.now().toEpochMilli();
			data.put(now, COMPONENT_ID + "/Connections",
					new JsonPrimitive(this.server != null ? this.server.getConnections().size() : 0));
			this.timedataManager.write(EDGE_ID, new TimestampedDataNotification(data));
		}, 10, 10, TimeUnit.SECONDS);
		startServer();
	
	}

	@Deactivate
	private void deactivate() {
		ThreadPoolUtils.shutdownAndAwaitTermination(this.debugLogExecutor, 0);
		this.stopServer();
	}

	/**
	 * Create and start new server.
	 *
	 * @param port      the port
	 * @param poolSize  number of threads dedicated to handle the tasks
	 * @param debugMode activate a regular debug log about the state of the tasks
	 */
	private synchronized void startServer(int port, int poolSize, DebugMode debugMode) {
		if(this.server != null) {
			return;
		}
		this.server = new WebsocketServer(this, "Ui.Websocket", port, poolSize, debugMode);
		this.server.start();
	}

	/**
	 * Stop existing websocket server.
	 */
	private synchronized void stopServer() {
		if (this.server != null) {
			this.server.stop();
			this.server = null;
		}
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	protected void logWarn(Logger log, String message) {
		super.logWarn(log, message);
	}

	@Override
	protected void logError(Logger log, String message) {
		super.logError(log, message);
	}

	@Override
	public void send(String token, JsonrpcNotification notification) throws OpenemsNamedException {
		var wsData = this.getWsDataForTokenOrError(token);
		wsData.send(notification);
	}

	@Override
	public CompletableFuture<JsonrpcResponseSuccess> send(String token, JsonrpcRequest request)
			throws OpenemsNamedException {
		var wsData = this.getWsDataForTokenOrError(token);
		return wsData.send(request);
	}

	@Override
	public void sendBroadcast(String edgeId, JsonrpcNotification notification) throws OpenemsNamedException {
		var wsDatas = this.getWsDatasForEdgeId(edgeId);
		OpenemsNamedException exception = null;
		for (WsData wsData : wsDatas) {
			if (!wsData.isEdgeSubscribed(edgeId)) {
				continue;
			}
			try {
				wsData.send(notification);
			} catch (OpenemsNamedException e) {
				exception = e;
			}
		}
		if (exception != null) {
			throw exception;
		}
	}

	/**
	 * Gets the WebSocket connection attachment for a UI token.
	 *
	 * @param token the UI token
	 * @return the WsData
	 * @throws OpenemsNamedException if there is no connection with this token
	 */
	private WsData getWsDataForTokenOrError(String token) throws OpenemsNamedException {
		var connections = this.server.getConnections();
		for (var websocket : connections) {
			WsData wsData = websocket.getAttachment();
			var thisToken = wsData.getToken();
			if (thisToken.isPresent() && thisToken.get().equals(token)) {
				return wsData;
			}
		}
		throw OpenemsError.BACKEND_NO_UI_WITH_TOKEN.exception(token);
	}

	/**
	 * Gets the WebSocket connection attachments of all connections accessing an
	 * Edge-ID.
	 *
	 * @param edgeId the Edge-ID
	 * @return the WsDatas; empty list if there are none
	 */
	private List<WsData> getWsDatasForEdgeId(String edgeId) {
		var result = new ArrayList<WsData>();
		var connections = this.server.getConnections();
		for (var websocket : connections) {
			WsData wsData = websocket.getAttachment();
			if (wsData == null) {
				continue;
			}
			// get attachment User-ID
			var userIdOpt = wsData.getUserId();
			if (userIdOpt.isPresent()) {
				var userId = userIdOpt.get();
				// get User for User-ID
				var userOpt = this.metadata.getUser(userId);
				if (userOpt.isPresent()) {
					var user = userOpt.get();
					var edgeRoleOpt = user.getRole(edgeId);
					if (edgeRoleOpt.isPresent()) {
						// User has access to this Edge-ID
						result.add(wsData);
					}
				}
			}
		}
		return result;
	}

	@Override
	public void handleEvent(Event event) {
		switch (event.getTopic()) {
		case Metadata.Events.AFTER_IS_INITIALIZED:
			startServer();
			break;
		}
	}
	
	private void startServer() {
		this.startServer(this.config.port(), this.config.poolSize(), this.config.debugMode());
	}

	@Override
	public void sendSubscribedChannels(String edgeId, EdgeCache edgeCache) {
		var connections = this.server.getConnections();
		for (var websocket : connections) {
			WsData wsData = websocket.getAttachment();
			if (wsData != null) {
				wsData.sendSubscribedChannels(edgeId, edgeCache);
			}
		}
	}

	/**
	 * Gets the authenticated User or throws an Exception if User is not
	 * authenticated.
	 *
	 * @param wsData  the WebSocket attachment
	 * @param request the {@link AbstractJsonrpcRequest}
	 * @return the {@link User}
	 * @throws OpenemsNamedException if User is not authenticated
	 */
	public User assertUser(WsData wsData, AbstractJsonrpcRequest request) throws OpenemsNamedException {
		var userIdOpt = wsData.getUserId();
		if (!userIdOpt.isPresent()) {
			throw OpenemsError.COMMON_USER_NOT_AUTHENTICATED
					.exception("User-ID is empty. Ignoring request [" + request.getMethod() + "]");
		}
		var userOpt = this.metadata.getUser(userIdOpt.get());
		if (!userOpt.isPresent()) {
			throw OpenemsError.COMMON_USER_NOT_AUTHENTICATED.exception("User with ID [" + userIdOpt.get()
					+ "] is unknown. Ignoring request [" + request.getMethod() + "]");
		}
		return userOpt.get();
	}
}
