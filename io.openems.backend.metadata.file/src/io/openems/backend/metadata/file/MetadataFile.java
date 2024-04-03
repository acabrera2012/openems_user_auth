package io.openems.backend.metadata.file;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.util.Strings;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.backend.common.metadata.AbstractMetadata;
import io.openems.backend.common.metadata.AlertingSetting;
import io.openems.backend.common.metadata.Edge;
import io.openems.backend.common.metadata.EdgeHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.SimpleEdgeHandler;
import io.openems.backend.common.metadata.User;
import io.openems.common.OpenemsOEM;
import io.openems.common.event.EventReader;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.GetEdgesRequest.PaginationOptions;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import io.openems.common.utils.JsonUtils;
import io.openems.common.utils.StringUtils;

/**
 * This implementation of MetadataService reads Edges configuration from a file.
 * The layout of the file is as follows:
 *
 * <pre>
 * {
 *   edges: {
 *     [edgeId: string]: {
 *       comment: string,
 *       apikey: string
 *       setuppassword?: string
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>
 * This implementation does not require any login. It always serves the same
 * user, which has 'ADMIN'-permissions on all given Edges.
 */
@Designate(ocd = Config.class, factory = false)
@Component(//
		name = "Metadata.File", //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		immediate = true //
)
@EventTopics({ //
		Edge.Events.ON_SET_CONFIG //
})
public class MetadataFile extends AbstractMetadata implements Metadata, EventHandler {
	private static final Pattern NAME_NUMBER_PATTERN = Pattern.compile("[^0-9]+([0-9]+)$");

	private static Language LANGUAGE = Language.EN;

	private final Logger log = LoggerFactory.getLogger(MetadataFile.class);
	private final Map<String, MyEdge> edges = new HashMap<>();
	private final SimpleEdgeHandler edgeHandler = new SimpleEdgeHandler();
	private final Map<String, UserConfig> userConfigMap = new HashMap<>();
	private final Map<String, User> loginUsers = new HashMap<>();
	private final AtomicInteger nextEdgeId = new AtomicInteger(-1);

	private @Reference EventAdmin eventAdmin;
	private String path = "";

	public MetadataFile() {
		super("Metadata.File");
	}

	private void cleanData() {
		edges.clear();
		userConfigMap.clear();
		loginUsers.clear();
		nextEdgeId.set(-1);
	}

	@Activate
	private void activate(Config config) {
		this.log.info("Activate [path=" + config.path() + "]");
		this.path = config.path();

		// Read the data async
		CompletableFuture.runAsync(() -> {
			this.refreshData();
		});
	}

	@Deactivate
	private void deactivate() {
		this.logInfo(this.log, "Deactivate");
	}

	@Override
	public User authenticate(String username, String password) throws OpenemsNamedException {
		this.log.info("authenticate: u:" + username + "-p:" + password);
		var userConfig = userConfigMap.get(username);
		if(userConfig != null && Objects.equals(userConfig.getPassword(), password)) {
			var newUser = generateUser(userConfig);
			loginUsers.put(newUser.getToken(), newUser);
			return newUser;
		}
		throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
	}

	@Override
	public User authenticate(String token) throws OpenemsNamedException {
		var user =  loginUsers.get(token);
		if(user != null) {
			return user;
		}
		throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
	}

	@Override
	public void logout(User user) {
		loginUsers.remove(user.getToken());
	}

	private static Optional<Integer> parseNumberFromName(String name) {
		try {
			var matcher = NAME_NUMBER_PATTERN.matcher(name);
			if (matcher.find()) {
				var nameNumberString = matcher.group(1);
				return Optional.ofNullable(Integer.parseInt(nameNumberString));
			}
		} catch (NullPointerException e) {
			/* ignore */
		}
		return Optional.empty();
	}

	@Override
	public Optional<String> getEdgeIdForApikey(String apikey) {
		this.log.info("get getEdgeIdForApikey, apikey: " + apikey);
		var edgeOpt = this.edges.values().stream() //
				.filter(edge -> apikey.equals(edge.getApikey())) //
				.findFirst();
		if (edgeOpt.isPresent()) {
			return Optional.ofNullable(edgeOpt.get().getId());
		}
		// not found. Is apikey a valid Edge-ID?
		var idOpt = parseNumberFromName(apikey);
		int id;
		String edgeId;
		String setupPassword;
		if (idOpt.isPresent()) {
			edgeId = apikey;
			id = idOpt.get();
		} else {
			// create new ID
			id = this.nextEdgeId.incrementAndGet();
			edgeId = "edge" + id;
		}
		setupPassword = edgeId;

		var edge = new MyEdge(this, edgeId, apikey, setupPassword, "OpenEMS Edge #" + id, "", "");
		this.edges.put(edgeId, edge);
		this.log.info("add MyEdge: " + edgeId);
		return Optional.ofNullable(edgeId);

	}

	@Override
	public synchronized Optional<Edge> getEdgeBySetupPassword(String setupPassword) {
		for (MyEdge edge : this.edges.values()) {
			if (edge.getSetupPassword().equals(setupPassword)) {
				return Optional.of(edge);
			}
		}
		return Optional.empty();
	}

	@Override
	public synchronized Optional<Edge> getEdge(String edgeId) {
		Edge edge = this.edges.get(edgeId);
		return Optional.ofNullable(edge);
	}

	@Override
	public Optional<User> getUser(String userId) {
		return loginUsers.values().stream().filter(i -> Objects.equals(i.getId(), userId)).findFirst();
	}

	@Override
	public synchronized Collection<Edge> getAllOfflineEdges() {
		return this.edges.values().stream().filter(Edge::isOffline).collect(Collectors.toUnmodifiableList());
	}

	private StringBuilder readFile(String path) throws IOException {
		var sb = new StringBuilder();
		String line;
		var br = new BufferedReader(new FileReader(path));
		while ((line = br.readLine()) != null) {
			sb.append(line);
		}
		br.close();
		return sb;
	}

	private synchronized void refreshData() {
		try {
			StringBuilder sb = readFile(this.path);
			var config = JsonUtils.parse(sb.toString()).getAsJsonArray();
			if(config.isEmpty()) {
				this.logInfo(this.log, "There is no user");
				return;
			}
			cleanData();
			for(JsonElement e : config.asList()) {
				String username = JsonUtils.getAsString(e, "username");
				String name = JsonUtils.getAsString(e, "name");
				String password = JsonUtils.getAsString(e, "password");
				String role = JsonUtils.getAsString(e, "role");
				String edgeApikey = JsonUtils.getAsString(e, "edgeApikey", "");
				userConfigMap.put(username, new UserConfig(username, name, password, Role.getRole(role), edgeApikey));
				this.logInfo(this.log, "User[username: " + username + ", role: " + role + "] has been added");
			}

		} catch (IOException e) {
			this.logWarn(this.log, "Unable to read file [" + this.path + "]: " + e.getMessage());
			e.printStackTrace();
			return;
		} catch (OpenemsNamedException e) {
			this.logWarn(this.log, "Unable to JSON-parse file [" + this.path + "]: " + e.getMessage());
			e.printStackTrace();
			return;
		}
		this.logInfo(this.log, "setInitialized");
		this.setInitialized();
	}


	private User generateUser(UserConfig userConfig) {
		return new User(userConfig.getUsername(), userConfig.getName(), UUID.randomUUID().toString(),
				MetadataFile.LANGUAGE, userConfig.getRole(), Strings.isEmpty(userConfig.getAdgeApikey()) && this.edges.size() > 1);
	}

	@Override
	public void addEdgeToUser(User user, Edge edge) throws OpenemsNamedException {
		throw new UnsupportedOperationException("FileMetadata.addEdgeToUser() is not implemented");
	}

	private String[] getFirstLastNames(String name) {
		String lastName = "";
		String firstName= "";
		if(name.split("\\w+").length>1){

			lastName = name.substring(name.lastIndexOf(" ")+1);
			firstName = name.substring(0, name.lastIndexOf(' '));
		}
		else{
			firstName = name;
		}
		return new String[] {firstName, lastName};
	}

	@Override
	public Map<String, Object> getUserInformation(User user) throws OpenemsNamedException {
		//throw new UnsupportedOperationException("FileMetadata.getUserInformation() is not implemented");
		String[] names = getFirstLastNames(Strings.isBlank(user.getName()) ? "" : user.getName().trim());
		return Map.of("name", user.getName(), "username", user.getId(),
				"firstname", names[0], "lastname", names[1]);
	}

	@Override
	public void setUserInformation(User user, JsonObject jsonObject) throws OpenemsNamedException {
		throw new UnsupportedOperationException("FileMetadata.setUserInformation() is not implemented");
	}

	@Override
	public byte[] getSetupProtocol(User user, int setupProtocolId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("FileMetadata.getSetupProtocol() is not implemented");
	}

	@Override
	public JsonObject getSetupProtocolData(User user, String edgeId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("FileMetadata.getSetupProtocolData() is not implemented");
	}

	@Override
	public int submitSetupProtocol(User user, JsonObject jsonObject) {
		throw new UnsupportedOperationException("FileMetadata.submitSetupProtocol() is not implemented");
	}

	@Override
	public void registerUser(JsonObject jsonObject, OpenemsOEM.Manufacturer oem) throws OpenemsNamedException {
		throw new UnsupportedOperationException("FileMetadata.registerUser() is not implemented");
	}

	@Override
	public void updateUserLanguage(User user, Language locale) throws OpenemsNamedException {
		MetadataFile.LANGUAGE = locale;
	}

	@Override
	public EdgeHandler edge() {
		return this.edgeHandler;
	}

	@Override
	public EventAdmin getEventAdmin() {
		return this.eventAdmin;
	}

	@Override
	public void handleEvent(Event event) {
		var reader = new EventReader(event);

		switch (event.getTopic()) {
		case Edge.Events.ON_SET_CONFIG:
			this.edgeHandler.setEdgeConfigFromEvent(reader);
			break;
		}
	}

	@Override
	public Optional<String> getSerialNumberForEdge(Edge edge) {
		throw new UnsupportedOperationException("FileMetadata.getSerialNumberForEdge() is not implemented");
	}

	@Override
	public List<AlertingSetting> getUserAlertingSettings(String edgeId) {
		throw new UnsupportedOperationException("FileMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public AlertingSetting getUserAlertingSettings(String edgeId, String userId) throws OpenemsException {
		throw new UnsupportedOperationException("FileMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public void setUserAlertingSettings(User user, String edgeId, List<AlertingSetting> users) {
		throw new UnsupportedOperationException("FileMetadata.setUserAlertingSettings() is not implemented");
	}

	@Override
	public Map<String, Role> getPageDevice(User user, PaginationOptions paginationOptions)
			throws OpenemsNamedException {
		var pagesStream = this.edges.values().stream();
		final var query = paginationOptions.getQuery();
		if (query != null) {
			pagesStream = pagesStream.filter(//
					edge -> StringUtils.containsWithNullCheck(edge.getId(), query) //
							|| StringUtils.containsWithNullCheck(edge.getComment(), query) //
							|| StringUtils.containsWithNullCheck(edge.getProducttype(), query) //
			);
		}
		UserConfig userConfig = userConfigMap.get(user.getId());
		if(userConfig != null && Strings.isNotEmpty(userConfig.getAdgeApikey()) && Role.GUEST == user.getGlobalRole()) {
			pagesStream = pagesStream.filter(t -> Objects.equals(t.getApikey(), userConfig.getAdgeApikey()));
		}
		return pagesStream //
				.sorted((s1, s2) -> s1.getId().compareTo(s2.getId())) //
				.skip(paginationOptions.getPage() * paginationOptions.getLimit()) //
				.limit(paginationOptions.getLimit()) //
				.peek(t -> user.setRole(t.getId(), user.getGlobalRole())) //
				.collect(Collectors.toMap(t -> t.getId(), t -> user.getGlobalRole())); //
	}

	@Override
	public Role getRoleForEdge(User user, String edgeId) throws OpenemsNamedException {
		return user.getGlobalRole();
	}

}
