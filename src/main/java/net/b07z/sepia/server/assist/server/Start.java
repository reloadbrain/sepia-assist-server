package net.b07z.sepia.server.assist.server;

import static spark.Spark.*;

import java.security.Policy;
import java.util.Date;

import org.json.simple.JSONObject;

import net.b07z.sepia.server.assist.answers.DefaultReplies;
import net.b07z.sepia.server.assist.endpoints.AccountEndpoint;
import net.b07z.sepia.server.assist.endpoints.AssistEndpoint;
import net.b07z.sepia.server.assist.endpoints.AuthEndpoint;
import net.b07z.sepia.server.assist.endpoints.RemoteActionEndpoint;
import net.b07z.sepia.server.assist.endpoints.SdkEndpoint;
import net.b07z.sepia.server.assist.endpoints.TtsEndpoint;
import net.b07z.sepia.server.assist.endpoints.UserDataEndpoint;
import net.b07z.sepia.server.assist.endpoints.UserManagementEndpoint;
import net.b07z.sepia.server.assist.interviews.InterviewServicesMap;
import net.b07z.sepia.server.assist.messages.Clients;
import net.b07z.sepia.server.assist.parameters.ParameterConfig;
import net.b07z.sepia.server.assist.tools.DateTimeConverters;
import net.b07z.sepia.server.assist.users.Authenticator;
import net.b07z.sepia.server.assist.users.ID;
import net.b07z.sepia.server.assist.users.User;
import net.b07z.sepia.server.assist.workers.Workers;
import net.b07z.sepia.server.core.data.Role;
import net.b07z.sepia.server.core.endpoints.CoreEndpoints;
import net.b07z.sepia.server.core.server.ConfigDefaults;
import net.b07z.sepia.server.core.server.RequestGetOrFormParameters;
import net.b07z.sepia.server.core.server.RequestParameters;
import net.b07z.sepia.server.core.server.RequestPostParameters;
import net.b07z.sepia.server.core.server.SparkJavaFw;
import net.b07z.sepia.server.core.server.Validate;
import net.b07z.sepia.server.core.tools.DateTime;
import net.b07z.sepia.server.core.tools.Debugger;
import net.b07z.sepia.server.core.tools.JSON;
import net.b07z.sepia.server.core.tools.SandboxSecurityPolicy;
import net.b07z.sepia.server.core.tools.Security;
import spark.Request;
import spark.Response;

/**
 * Main class that starts the server.
 * 
 * @author Florian Quirin
 *
 */
public class Start {

	//stuff
	public static String startGMT = "";
	public static String serverType = "";
	
	public static final String LIVE_SERVER = "live";
	public static final String TEST_SERVER = "test";
	public static final String CUSTOM_SERVER = "custom";
	
	public static boolean isSSL = false;
	public static String keystorePwd = "13371337";
	
	/**
	 * Load configuration file.
	 * @param serverType - live, test, custom
	 */
	public static void loadConfigFile(String serverType){
		if (serverType.equals(TEST_SERVER)){
			Config.configFile = "Xtensions/assist.test.properties";
		}else if (serverType.equals(CUSTOM_SERVER)){
			Config.configFile = "Xtensions/assist.custom.properties";
		}else if (serverType.equals(LIVE_SERVER)){
			Config.configFile = "Xtensions/assist.properties";
		}else{
			throw new RuntimeException("INVALID SERVER TYPE: " + serverType);
		}
		Config.loadSettings(Config.configFile);
	}
	
	/**
	 * Check arguments and load settings correspondingly.
	 * @param args - parameters submitted to main method
	 */
	public static void loadSettings(String[] args){
		//check arguments
		serverType = TEST_SERVER;
		for (String arg : args){
			if (arg.equals("setup")){
				//Start setup
				try {	Setup.main(args);	} catch (Exception e) {}
				System.exit(0);
				return;
			
			}else if (arg.equals("--test")){
				//Test system
				serverType = TEST_SERVER;
			}else if (arg.equals("--live")){
				//Live system
				serverType = LIVE_SERVER;
			}else if (arg.equals("--my") || arg.equals("--custom")){
				//Custom system
				serverType = CUSTOM_SERVER;
			}else if (arg.equals("--ssl")){
				//SSL
				isSSL = true;
			}else if (arg.startsWith("keystorePwd=")){
				//Java key-store password - TODO: maybe not the best way to load the pwd ...
				keystorePwd = arg.replaceFirst(".*?=", "").trim();
			}
		}
		//set security
		Policy.setPolicy(new SandboxSecurityPolicy());
		System.setSecurityManager(new SecurityManager());
		ConfigServices.setupSandbox();
		if (isSSL){
			secure(Config.xtensionsFolder + "SSL/ssl-keystore.jks", keystorePwd, null, null);
		}
		
		//load configuration
		loadConfigFile(serverType);
		Debugger.println("--- Running " + Config.SERVERNAME + " with " + serverType.toUpperCase() + " settings ---", 3);
		
		//host files?
		if (Config.hostFiles){
			staticFiles.externalLocation(Config.webServerFolder);
			Debugger.println("Web-server is active and uses folder: " + Config.webServerFolder, 3);
		}
		
		//SETUP CORE-TOOLS
		JSONObject coreToolsConfig = JSON.make(
				"defaultAssistAPI", Config.endpointUrl,
				"defaultTeachAPI", Config.teachApiUrl,
				"clusterKey", Config.clusterKey,
				"defaultAssistantUserId", Config.assistantId,
				"privacyPolicy", Config.privacyPolicyLink
		);
		//common microservices API-Keys - TODO: move? or add geo-coders?
		JSON.put(coreToolsConfig, "DeutscheBahnOpenApiKey", Config.deutscheBahnOpenApi_key);
		ConfigDefaults.setupCoreTools(coreToolsConfig);
		
		//Check core-tools settings
		if (!ConfigDefaults.areCoreToolsSet()){
			throw new RuntimeException("Core-tools are NOT set properly!");
		}
	}
	
	/**
	 * Setup server with port, cors, error-handling etc.. 
	 */
	public static void setupServer(){
		//start by getting GMT date
		Date date = new Date();
		startGMT = DateTime.getGMT(date, "dd.MM.yyyy' - 'HH:mm:ss' - GMT'");
		Debugger.println("Starting Assistant-API server " + Config.apiVersion + " (" + serverType + ")", 3);
		Debugger.println("date: " + startGMT, 3);
		
		//email warnings?
		if (!Config.emailBCC.isEmpty()){
			Debugger.println("WARNING: Emails are sent to " + Config.emailBCC + " for debugging issues!", 3);
		}
				
		/*
		//TODO: do we need to set this? https://wiki.eclipse.org/Jetty/Howto/High_Load
		int maxThreads = 8;
		int minThreads = 2;
		int timeOutMillis = 30000;
		threadPool(maxThreads, minThreads, timeOutMillis)
		 */
		
		try {
			port(Integer.valueOf(System.getenv("PORT")));
			Debugger.println("server running on port: " + Integer.valueOf(System.getenv("PORT")), 3);
		}catch (Exception e){
			int port = Config.serverPort; 	//default is 20721
			port(port);
			Debugger.println("server running on port "+ port, 3);
		}
		
		//set access-control headers to enable CORS
		if (Config.enableCORS){
			SparkJavaFw.enableCORS("*", "*", "*");
		}

		//do something before end-point evaluation - e.g. authentication
		before((request, response) -> {
			//System.out.println("BEFORE TEST 1"); 		//DEBUG
		});
		
		//ERROR handling - TODO: improve
		SparkJavaFw.handleError();
	}
	
	/**
	 * Setup services and parameters by connecting commands to service modules etc.
	 */
	public static void setupServicesAndParameters(){
		InterviewServicesMap.load();		//services connected to interviews
		InterviewServicesMap.test();		//test if all services can be loaded
		ParameterConfig.setup(); 			//connect parameter names to handlers and other stuff
		ParameterConfig.test();				//test if all parameters can be loaded
		DefaultReplies.setupDefaults(); 	//setup default question mapping for parameters and stuff
	}
	
	/**
	 * All kinds of things that should be loaded on startup.
	 */
	public static void setupModules(){
		Config.setupDatabases(); 		//DB modules
		Config.setupAnswers();			//answers
		Config.setupCommands();		//predefined commands
		Config.setupChats(); 			//predefined chats
		Config.setup_nlu_steps(); 		//interpretation chain
		Workers.setupWorkers(); 		//setup and start selected workers
		if (Config.connectToWebSocket){
			Clients.setupSocketMessenger();		//setup webSocket messenger and connect
		}
	}
	
	/**
	 * Check existence of universal accounts (superuser and assistant).
	 */
	public static void checkCoreAccounts(){
		if (!Config.validateUniversalToken()){
			Debugger.println("Server token not valid!", 1);
			Debugger.println("Administrator account could not be validated, CANNOT proceed! Please check database access and accounts.", 1);
			System.exit(0);
			//throw new RuntimeException("Administrator account could not be validated, CANNOT proceed! Please check database access and accounts.");
		}else{
			Debugger.println("Server token validated", 3);
		}
		if (!Config.validateAssistantToken()){
			Debugger.println("Assistant token not valid!", 1);
			Debugger.println("Assistant account could not be validated, CANNOT proceed! Please check database access and accounts.", 1);
			System.exit(0);
			//throw new RuntimeException("Assistant account could not be validated, CANNOT proceed! Please check database access and accounts.");
		}else{
			Debugger.println("Assistant token validated", 3);
		}
	}
	
	/**
	 * Defines the end-points that this server supports.
	 */
	public static void loadEndpoints(){
		//Server
		get("/online", (request, response) -> 				CoreEndpoints.onlineCheck(request, response));
		get("/ping", (request, response) -> 				CoreEndpoints.ping(request, response, Config.SERVERNAME));
		get("/validate", (request, response) -> 			CoreEndpoints.validateServer(request, response, 
																Config.SERVERNAME, Config.apiVersion, Config.localName, Config.localSecret));
		post("/hello", (request, response) -> 				helloWorld(request, response));
		post("/cluster", (request, response) ->				clusterData(request, response));
		post("/config", (request, response) -> 				configServer(request, response));
		
		//Accounts and assistant
		post("/user-management", (request, response) ->		UserManagementEndpoint.userManagementAPI(request, response));
		post("/authentication", (request, response) -> 		AuthEndpoint.authenticationAPI(request, response));
		post("/account", (request, response) ->				AccountEndpoint.accountAPI(request, response));
		post("/userdata", (request, response) ->			UserDataEndpoint.userdataAPI(request, response));
		post("/interpret", (request, response) -> 			AssistEndpoint.interpreterAPI(request, response));
		post("/answer", (request, response) -> 				AssistEndpoint.answerAPI(request, response));
		post("/events", (request, response) -> 				AssistEndpoint.events(request, response));
		
		//TTS
		post("/tts", (request, response) -> 				TtsEndpoint.ttsAPI(request, response));
		post("/tts-info", (request, response) -> 			TtsEndpoint.ttsInfo(request, response));
		
		//SDK
		get("/upload-service", (request, response) -> 		SdkEndpoint.uploadServiceGet(request, response));
		post("/upload-service", (request, response) -> 		SdkEndpoint.uploadServicePost(request, response));
		
		//Remote controls
		post("/remote-action", (request, response) ->		RemoteActionEndpoint.remoteActionAPI(request, response));
	}
	
	/**
	 * Stuff to add to the default statistics output (e.g. from end-point hello).
	 */
	public static String addToStatistics(){
		//add stuff here
		return "";
	}
	
	/**
	 * Load updates to the framework that are placed here to maintain compatibility with projects that use SEPIA.<br>
	 * Stuff in here should be moved to a proper place as soon as all developers have been informed of the changes.
	 */
	public static void loadUpdates(){
		//add stuff here
	}
	
	/**
	 * MAIN METHOD TO START SERVER
	 */
	public static void main(String[] args) {
		
		//load settings
		loadSettings(args);
		
		//load statics and workers and setup modules (loading stuff to memory etc.)
		setupModules();
		
		//setup services and parameters by connecting commands etc.
		setupServicesAndParameters();
		
		//check existence of universal accounts (superuser and assistant)
		checkCoreAccounts();
		
		//load updates to the framework that have no specific place yet
		loadUpdates();
		
		//setup server with port, cors and error handling etc. 
		setupServer();
		
		//SERVER END-POINTS
		loadEndpoints();
	}
	
	/**
	 * ---HELLO WORLD---<br>
	 * End-point to get statistics of the server.
	 */
	public static String helloWorld(Request request, Response response){
		//prepare parameters
		RequestParameters params = new RequestGetOrFormParameters(request);
		
		//authenticate
		Authenticator token = authenticate(params, request);
		if (!token.authenticated()){
			return SparkJavaFw.returnNoAccess(request, response, token.getErrorCode());
		}else{
			//create user
			User user = new User(null, token);
			//write basic statistics for user
			user.saveStatistics();
			
			//time now
			Date date = new Date();
			String nowGMT = DateTime.getGMT(date, "dd.MM.yyyy' - 'HH:mm:ss' - GMT'");
			String nowLocal = DateTimeConverters.getToday("dd.MM.yyyy' - 'HH:mm:ss' - LOCAL'", params.getString("time_local"));
			
			//get user role
			String reply;
			if (user.hasRole(Role.developer)){
				//stats
				reply = "Hello World!"
						+ "<br><br>"
						+ "Stats:<br>" +
								"<br>api: " + Config.apiVersion +
								"<br>started: " + startGMT +
								"<br>now: " + nowGMT + 
								"<br>local: " + nowLocal + "<br>" +
								"<br>host: " + request.host() +
								"<br>url: " + request.url() + "<br><br>" +
								Statistics.getInfo();
			}else{
				reply = "Hello World!";
			}
			JSONObject msg = new JSONObject();
			JSON.add(msg, "result", "success");
			JSON.add(msg, "reply", reply);
			return SparkJavaFw.returnResult(request, response, msg.toJSONString(), 200);
		}
	}
	
	/**
	 * ---ASSIST API CLUSTER DATA---<br>
	 * End-point to get some cluster-relevant data of the server.
	 */
	public static String clusterData(Request request, Response response){
		//NOTE: we use cluster-key authentication here 
		//and we DON'T check 'Config.allowInternalCalls' (since this is required data)
		
		//prepare parameters
		RequestParameters params = new RequestGetOrFormParameters(request);
		
		//authenticate 
		if (Validate.validateInternalCall(request, params.getString("sKey"), Config.clusterKey)){
			JSONObject msg = new JSONObject();
			JSON.add(msg, "result", "success");
			JSON.add(msg, "serverName", Config.SERVERNAME);
			JSON.add(msg, "assistantUserId", Config.assistantId);
			JSON.add(msg, "assistantName", Config.assistantName);
			return SparkJavaFw.returnResult(request, response, msg.toJSONString(), 200);
		}else{
			return SparkJavaFw.returnNoAccess(request, response);
		}
	}
	
	/**
	 * ---CONFIG SERVER---<br>
	 * End-point to remotely switch certain settings on run-time.
	 */
	public static String configServer(Request request, Response response){
		//check request origin
		if (!Config.allowGlobalDevRequests){
			if (!SparkJavaFw.requestFromPrivateNetwork(request)){
				JSONObject result = new JSONObject();
				JSON.add(result, "result", "fail");
				JSON.add(result, "error", "Not allowed to access service from outside the private network!");
				return SparkJavaFw.returnResult(request, response, result.toJSONString(), 200);
			}
		}
		//prepare parameters
		RequestParameters params = new RequestGetOrFormParameters(request);
		
		//authenticate
		Authenticator token = authenticate(params, request);
		if (!token.authenticated()){
			return SparkJavaFw.returnNoAccess(request, response, token.getErrorCode());
		}else{
			//create user
			User user = new User(null, token);
			
			//check role
			if (!user.hasRole(Role.superuser)){
				Debugger.println("Unauthorized access attempt to server config! User: " + user.getUserID(), 3);
				return SparkJavaFw.returnNoAccess(request, response);
			}
			
			//check actions
			
			//-answers
			String toggleAnswerModule = params.getString("answers");
			if (toggleAnswerModule != null && toggleAnswerModule.equals("toggle")){
				Config.toggleAnswerModule();
				Debugger.println("Config - answers module changed by user: " + user.getUserID(), 3);
			}
			//-commands
			String toggleSentencesDB = params.getString("useSentencesDB");
			if (toggleSentencesDB != null && toggleSentencesDB.equals("toggle")){
				if (Config.useSentencesDB){
					Config.useSentencesDB = false;
				}else{
					Config.useSentencesDB = true;
				}
				Debugger.println("Config - loading of DB commands was changed by user: " + user.getUserID(), 3);
			}
			//-email bcc
			String setEmailBCC = params.getString("setEmailBCC");
			if (setEmailBCC != null && setEmailBCC.equals("remove")){
				Config.emailBCC = "";
			}
			//-sdk
			String toggleSdk = params.getString("sdk");
			if (toggleSdk != null && toggleSdk.equals("toggle")){
				if (Config.enableSDK){
					Config.enableSDK = false;
				}else{
					Config.enableSDK = true;
				}
				Debugger.println("Config - sdk status changed by user: " + user.getUserID(), 3);
			}
			//-database
			String reloadDB = params.getString("reloadDB");
			String dbReloadMsg = "no-update"; 
			if (reloadDB != null && !reloadDB.isEmpty()){
				String[] dbCmd = reloadDB.split("-");
				if (dbCmd.length == 2){
					if (dbCmd[0].equals("es")){
						if (!dbCmd[1].isEmpty()){
							try{
								Setup.writeElasticsearchMapping(dbCmd[1]);
								dbReloadMsg = "reloaded:" + reloadDB;
							}catch(Exception e){
								dbReloadMsg = "reload-error:" + reloadDB;
							}
						}
					}
				}
			}
			
			JSONObject msg = new JSONObject();
			JSON.add(msg, "answerModule", Config.answerModule);
			JSON.add(msg, "useSentencesDB", Config.useSentencesDB);
			JSON.add(msg, "emailBCC", Config.emailBCC);
			JSON.add(msg, "sdk", Config.enableSDK);
			JSON.add(msg, "dbUpdate", dbReloadMsg);
			return SparkJavaFw.returnResult(request, response, msg.toJSONString(), 200);
		}
	}
	
	//------- common authentication methods -------
	
	/**
	 * Authenticate the user.
	 * @param request - the request (aka url parameters) sent to server.
	 * @return true or false
	 */
	private static Authenticator authenticate(String key, String client, Request metaInfo){
		//System.out.println("Client: " + client); 		//DEBUG
		if (key != null && !key.isEmpty()){
			String[] info = key.split(";",2);
			if (info.length == 2){
				String username = info[0].toLowerCase();
				String password = info[1];
				//password must be 64 or 65 char hashed version - THE CLIENT IS EXPECTED TO DO THAT!
				//65 char is the temporary token
				if ((password.length() == 64) || (password.length() == 65)){
					String idType = ID.autodetectType(username);
					if (idType.isEmpty()){
						return new Authenticator();
					}
					Authenticator token = new Authenticator(username, password, idType, client, metaInfo);
					return token;
				
				//some different auth. procedure?
				}else if (password.length() > 32){
					Authenticator token = new Authenticator(username, password, ID.Type.uid, client, metaInfo);
					return token;
				}
				
			}
		}
		Authenticator token = new Authenticator();
		return token;
	}
	public static Authenticator authenticate(Request request){
		return authenticate(new RequestGetOrFormParameters(request), request);
	}
	protected static Authenticator authenticate(Request request, boolean isFormData){
		if (isFormData){
			return authenticate(new RequestGetOrFormParameters(request), request);
		}else{
			return authenticate(new RequestPostParameters(request), request);
		}
	}
	public static Authenticator authenticate(RequestParameters params, Request metaInfo){
		String key = params.getString("KEY");
		if (key == null || key.isEmpty()){
			String guuid = params.getString("GUUID");
			String pwd = params.getString("PWD");
			if (guuid != null && pwd != null && !guuid.isEmpty() && !pwd.isEmpty()){
				key = guuid + ";" + Security.hashClientPassword(pwd);
			} 
		}
		String client_info = params.getString("client");
		if (client_info == null || client_info.isEmpty()){
			client_info = Config.defaultClientInfo;
		}
		return authenticate(key, client_info, metaInfo);
	}
}
