/*
 * Copyright 2016 predic8 GmbH, www.predic8.com
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.predic8.membrane.core.interceptor.oauth2;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCChildElement;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.Router;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.*;
import com.predic8.membrane.core.interceptor.AbstractInterceptor;
import com.predic8.membrane.core.interceptor.Outcome;
import com.predic8.membrane.core.interceptor.authentication.session.*;
import com.predic8.membrane.core.interceptor.authentication.session.SessionManager.Session;
import com.predic8.membrane.core.util.URIFactory;
import com.predic8.membrane.core.util.URLParamUtil;
import org.springframework.beans.factory.annotation.Required;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@MCElement(name = "oauth2authserver")
public class OAuth2AuthorizationServerInterceptor extends AbstractInterceptor {

    //public static final String AUTHORIZATION_CODE = "authorization_code";

    private String issuer;
    private String location;
    private String path;
    private String message;
    private boolean exposeUserCredentialsToSession;

    private UserDataProvider userDataProvider;
    private LoginDialog loginDialog;
    private SessionManager sessionManager;
    private AccountBlocker accountBlocker;
    private ClientList clientList;
    private TokenGenerator tokenGenerator;
    private ScopeList scopeList;
    private JwtGenerator jwtGenerator;
    private HashSet<String> supportedAuthorizationGrants = new HashSet<String>();

    JsonFactory jsonFactory = new JsonFactory();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    JsonGenerator jsonGenerator;

    private SecureRandom random = new SecureRandom();
    private URIFactory uriFactory;

    private HashMap<String, Session> authCodesToSession = new HashMap<String, Session>();
    private HashMap<String, Session> tokensToSession = new HashMap<String, Session>();

    @Override
    public void init(Router router) throws Exception {
        uriFactory = router.getUriFactory();
        jsonGenerator = jsonFactory.createGenerator(baos);
        if (userDataProvider == null)
            throw new Exception("No userDataProvider configured. - Cannot work without one.");
        if (getClientList() == null)
            throw new Exception("No clientList configured. - Cannot work without one.");
        if (sessionManager == null)
            sessionManager = new SessionManager();
        if (tokenGenerator == null)
            tokenGenerator = new BearerTokenGenerator();
        if (getScopeList() == null) {
            throw new Exception("No scopeList configured. - Cannot work without one");
        }
        userDataProvider.init(router);
        getClientList().init(router);
        getScopeList().init(router);
        jwtGenerator = new JwtGenerator();
        addSupportedAuthorizationGrants();
        loginDialog = new LoginDialog(getUserDataProvider(), null, getSessionManager(), getAccountBlocker(), getLocation(), getPath(), isExposeUserCredentialsToSession(), getMessage());
        loginDialog.init(router);
        sessionManager.init(router);
        new CleanupThread(sessionManager, accountBlocker).start();
    }

    private void addSupportedAuthorizationGrants() {
        supportedAuthorizationGrants.add("code");
        supportedAuthorizationGrants.add("token");
    }

    protected JsonGenerator getAndResetJsonGenerator() {
        baos.reset();
        return jsonGenerator;
    }

    protected String getStringFromJsonGenerator() throws IOException {
        jsonGenerator.flush();
        return baos.toString();
    }

    /**
     * @param params the number of strings passed as params need to be an even number. Params is an alternating list of strings containing of "name" "value" pairs.
     */
    public Outcome createParameterizedJsonErrorResponse(Exchange exc, String... params) throws IOException {
        if (params.length % 2 != 0)
            throw new IllegalArgumentException("The number of strings passed as params is not even");
        String json;
        synchronized (jsonGenerator) {
            JsonGenerator gen = getAndResetJsonGenerator();
            gen.writeStartObject();
            for (int i = 0; i < params.length; i += 2)
                gen.writeObjectField(params[i], params[i + 1]);
            gen.writeEndObject();
            json = getStringFromJsonGenerator();
        }

        exc.setResponse(Response.badRequest()
                .body(json)
                .contentType(MimeType.APPLICATION_JSON_UTF8)
                .dontCache()
                .build());

        return Outcome.RETURN;
    }

    public Outcome createParameterizedFormUrlencodedRedirect(Exchange exc, String state, String url) {
        if (state != null)
            url = url + "&state=" + state;
        exc.setResponse(Response.redirect(url, false).header(Header.CONTENT_TYPE, "application/x-www-form-urlencoded").bodyEmpty().dontCache().build());
        return Outcome.RETURN;
    }

    @Override
    public Outcome handleRequest(Exchange exc) throws Exception {

        SessionManager.Session s;
        synchronized (sessionManager) {
            s = sessionManager.getSession(exc.getRequest());
        }
        if (s != null && s.getUserAttributes() == null)
            s = null; // session was logged out

        if (exc.getRequestURI().startsWith("/favicon.ico")) {
            exc.setResponse(Response.badRequest().build());
            return Outcome.RETURN;
        }

        if (isOAuth2AuthCall(exc)) {
            return processAuthorizationEndpoint(exc, s);
        }
        if (isOAuth2TokenCall(exc)) {
            return processTokenEndpoint(exc);
        }
        if (isOAuth2UserinfoCall(exc)) {
            return processUserInfoCall(exc);
        }
        if (isOAuth2RevokeCall(exc)) {
            return processRevokationEndpoint(exc);
        }
        if (loginDialog.isLoginRequest(exc)) {
            loginDialog.handleLoginRequest(exc);
            extractSessionFromRequestAndAddToResponse(exc);

            return Outcome.RETURN;
        }
        if (exc.getRequestURI().equals("/") && s != null && s.isPreAuthorized()) {
            return returnCodeAfterAuthorization(exc, s);
        }
        return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
    }

    private Outcome processTokenEndpoint(Exchange exc) throws IOException {
        Map<String, String> params = extractBody(exc.getRequest());

        removeEmptyParams(params);

        if (!params.containsKey("code"))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        Session session;
        synchronized (authCodesToSession) {
            if (!authCodesToSession.containsKey(params.get("code"))) {
                return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
            }
            session = authCodesToSession.get(params.get("code"));
            authCodesToSession.remove(params.get("code")); // auth codes can only be used one time
        }
        String username;

        synchronized (session) {
            username = session.getUserName();
            session.getUserAttributes().putAll(params);
        }

        String givenClientId = params.get("client_id");
        String givenClientSecret = params.get("client_secret");

        if (givenClientId == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        if (givenClientSecret == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        Client client;
        try {
            client = clientList.getClient(givenClientId);
        } catch (Exception e) {
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_client");
        }

        if (!givenClientSecret.equals(client.getClientSecret()))
            return createParameterizedJsonErrorResponse(exc, "error", "unauthorized_client");

        String givenRedirect_uri = params.get("redirect_uri");
        if (givenRedirect_uri == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        if (!isAbsoluteUri(givenRedirect_uri))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        //return createInvalidRequestResponse(exc);

        String knownRedirect_uri = client.getCallbackUrl();
        if (!givenRedirect_uri.equals(knownRedirect_uri))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");


        String token = tokenGenerator.getToken(username, client.getClientId(), client.getClientSecret());

        String idToken = null;
        if (isOpenIdScope(getScope(session))) {
            //idToken = jwtGenerator.getIdTokenSigned(...) // TODO
        }

        synchronized (tokensToSession) {
            tokensToSession.put(token, session);
        }

        // TODO maybe undo this as the session is used internally
        session.getUserAttributes().remove("password");
        session.getUserAttributes().remove("client_secret");


        exc.setResponse(Response
                .ok()
                .body(getTokenJSONResponse(session, token, idToken))
                .contentType(MimeType.APPLICATION_JSON_UTF8)
                .dontCache()
                .build());

        return Outcome.RETURN;
    }

    private Outcome processAuthorizationEndpoint(Exchange exc, Session s) throws Exception {
        if (s == null) {
            return processRENAMEME(exc); // TODO
        }
        //openid specific

        Map<String, String> params = URLParamUtil.getParams(uriFactory, exc);

        removeEmptyParams(params);

        if (!params.containsKey("prompt"))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        String prompt = params.get("prompt");
        if (prompt.equals("login")) {
            s.clear();
            return redirectToOAuth2AuthEndpoint(exc);
        }
        if (prompt.equals("none") && !s.isAuthorized()) {
            return createParameterizedJsonErrorResponse(exc, "error", "login_required");
        }
        return createParameterizedJsonErrorResponse(exc, "error", "login_required");
    }

    private Outcome processRENAMEME(Exchange exc) throws Exception {
        Session s;
        Map<String, String> params = URLParamUtil.getParams(uriFactory, exc);
        removeEmptyParams(params);

        String givenState = params.get("state");

        String givenClientId = params.get("client_id");
        if (givenClientId == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        Client client;
        try {
            client = clientList.getClient(givenClientId);
        } catch (Exception e) {
            return createParameterizedJsonErrorResponse(exc, "error", "unauthorized_client");
        }

        // TODO new Object for given...

        String givenRedirect_uri = params.get("redirect_uri");
        if (givenRedirect_uri == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        if (!isAbsoluteUri(givenRedirect_uri))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        //return createInvalidRequestResponse(exc);

        String knownRedirect_uri = client.getCallbackUrl();
        if (!givenRedirect_uri.equals(knownRedirect_uri))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        //return createInvalidRequestResponse(exc);

        //openid specific
        if (params.containsKey("prompt")) {
            String prompt = params.get("prompt");
            if (prompt.equals("none"))
                return createParameterizedFormUrlencodedRedirect(exc, givenState, knownRedirect_uri + "?error=login_required");
        }


        exc.setResponse(new Response.ResponseBuilder().build());
        String givenSessionId = extractSessionId(extraxtSessionHeader(exc.getRequest()));
        synchronized (sessionManager) {
            s = sessionManager.createSession(exc, givenSessionId);
        }

        String givenAuthorizationGrant = params.get("response_type");
        if (givenAuthorizationGrant == null)
            return createParameterizedFormUrlencodedRedirect(exc, givenState, knownRedirect_uri + "?error=invalid_request");
        //return createInvalidRequestResponse(exc);

        if (!supportedAuthorizationGrants.contains(givenAuthorizationGrant))
            return createParameterizedFormUrlencodedRedirect(exc, givenState, knownRedirect_uri + "?error=unsupported_response_type");
        //return createUnsupportedResponseTypeResponse(exc);


        String givenScopes = params.get("scope");
        if (givenScopes == null)
            return createParameterizedFormUrlencodedRedirect(exc, givenState, knownRedirect_uri + "?error=invalid_request");

        String validScopes = verifyScopes(givenScopes);

        if (validScopes.isEmpty())
            return createParameterizedFormUrlencodedRedirect(exc, givenState, knownRedirect_uri + "?error=invalid_scope");
        //return createInvalidScopeResponse(exc);

        params.put("scope", validScopes);

        String invalidScopes = hasGivenInvalidScopes(givenScopes, validScopes);
        if (!invalidScopes.isEmpty())
            params.put("scope_invalid", invalidScopes);
        synchronized (s) {
            s.getUserAttributes().putAll(params);
        }
        return redirectToLoginWithSession(exc, extraxtSessionHeader(exc.getResponse()));
    }

    private Outcome returnCodeAfterAuthorization(Exchange exc, Session s) throws IOException {
        s.authorize();
        Client client;
        String sessionRedirectUrl;
        synchronized (s) {
            client = clientList.getClient(s.getUserAttributes().get("client_id"));
            sessionRedirectUrl = s.getUserAttributes().get("redirect_uri");
        }
        if (client.getCallbackUrl().equals(sessionRedirectUrl)) {
            if (getResponseType(s).equals("code")) {
                String code = generateAuthorizationCode();
                authCodesToSession.put(code, s);
                return respondWithAuthorizationCodeAndRedirect(exc, code);
            }
            if (getResponseType(s).equals("token")) {
                return respondWithTokenAndRedirect(exc, generateAccessToken(s, client), tokenGenerator.getTokenType());
            }
            return createParameterizedJsonErrorResponse(exc, "error", "unsupported_response_type");
        }
        return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
    }

    private Outcome processRevokationEndpoint(Exchange exc) throws Exception {
        Map<String, String> params = URLParamUtil.getParams(uriFactory, exc);

        if (!params.containsKey("token"))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        if (!params.containsKey("client_id"))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");
        if (!params.containsKey("client_secret"))
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_request");

        Session session = tokensToSession.get(params.get("token"));
        if (session == null)
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_grant");

        try {
            tokenGenerator.invalidateToken(params.get("token"), params.get("client_id"), params.get("client_secret"));
        } catch (Exception e) {
            return createParameterizedJsonErrorResponse(exc, "error", "invalid_grant");
        }
        session.clear();
        exc.setResponse(Response
                .ok()
                .bodyEmpty()
                .build());
        return Outcome.RETURN;
    }

    private Outcome processUserInfoCall(Exchange exc) throws IOException {
        String authHeader = exc.getRequest().getHeader().getAuthorization();
        if (authHeader == null)
            return createNoBodyWwwAuthenticateErrorResponse(exc, 400, tokenGenerator.getTokenType() + " error=\"invalid_request\"");
        String token = authHeader.split(" ")[1];

        if (!tokensToSession.containsKey(token)) {
            return createNoBodyWwwAuthenticateErrorResponse(exc, 401, tokenGenerator.getTokenType() + " error=\"invalid_token\"");
        }

        Session session;
        synchronized (tokensToSession) {
            session = tokensToSession.get(token);
        }

        exc.setResponse(Response
                .ok()
                .body(getJSONStringGiveMeABetterName(session))
                .contentType(MimeType.APPLICATION_JSON_UTF8)
                .build());

        return Outcome.RETURN;
    }

    private String getResponseType(Session s) {
        return s.getUserAttributes().get("response_type");
    }

    private String generateAccessToken(Session s, Client client) {
        synchronized (s) {
            return tokenGenerator.getToken(s.getUserName(), client.getClientId(), client.getClientSecret());
        }
    }

    private String getJSONStringGiveMeABetterName(Session session) throws IOException {

        Map<String, String> scopeProperties = getScopeProperties(session); // Do not Inline! Synchronize

        synchronized (jsonGenerator) {
            JsonGenerator gen = getAndResetJsonGenerator();
            gen.writeStartObject();

            for (String property : scopeProperties.keySet())
                gen.writeObjectField(property, scopeProperties.get(property));

            gen.writeEndObject();
            return getStringFromJsonGenerator();
        }
    }

    private Map<String, String> getScopeProperties(Session session) {
        synchronized (session) {
            String[] scopes = session.getUserAttributes().get("scope").split(" ");
            return scopeList.getScopes(session.getUserAttributes(), scopes);
        }
    }

    private String getTokenJSONResponse(Session session, String token, String idToken) throws IOException {
        String json;
        synchronized (jsonGenerator) {
            JsonGenerator gen = getAndResetJsonGenerator();
            gen.writeStartObject();
            gen.writeObjectField("access_token", token);
            gen.writeObjectField("token_type", tokenGenerator.getTokenType());
            //gen.writeObjectField("expires_in", "null"); // TODO change this
            gen.writeObjectField("scope", session.getUserAttributes().get("scope"));
            if (idToken != null)
                gen.writeObjectField("id_token:", idToken);
            gen.writeEndObject();
            json = getStringFromJsonGenerator();
        }
        return json;
    }

    private String getScope(Session session) {
        synchronized (session) {
            return session.getUserAttributes().get("scope");
        }
    }

    private void removeEmptyParams(Map<String, String> params) {
        for (String paramName : params.keySet()) {
            if (params.get(paramName).isEmpty())
                params.remove(paramName);
        }
    }

    private boolean isOpenIdScope(String scope) {
        if (scope.contains("openid")) {
            String[] split = scope.split(" ");
            for (String singleScope : split)
                if (singleScope.equals("openid"))
                    return true;
        }
        return false;
    }

    private Outcome redirectToOAuth2AuthEndpoint(Exchange exc) {
        exc.setResponse(Response.redirect(exc.getRequestURI(), false).dontCache().bodyEmpty().build());
        return Outcome.RETURN;
    }

    private Outcome createNoBodyWwwAuthenticateErrorResponse(Exchange exc, int code, String wwwAuthenticateValues) {
        Response.ResponseBuilder resp;
        switch (code) {
            case 400:
                resp = Response.badRequest();
                break;
            case 401:
                resp = Response.unauthorized();
                break;
            case 403:
                resp = Response.forbidden();
                break;
            default:
                resp = Response.badRequest();
        }
        exc.setResponse(resp.bodyEmpty().header(Header.WWW_AUTHENTICATE, wwwAuthenticateValues).build());
        return Outcome.RETURN;
    }

    private Outcome respondWithTokenAndRedirect(Exchange exc, String token, String tokenType) {
        Session s = sessionManager.getSession(exc.getRequest());
        String state;
        String redirectUrl;
        String scope;
        synchronized (s) {
            state = s.getUserAttributes().get("state");
            redirectUrl = s.getUserAttributes().get("redirect_uri");
            scope = s.getUserAttributes().get("scope");
        }

        exc.setResponse(Response.
                redirect(redirectUrl + "?access_token=" + token + (state == null ? "" : "&state=" + state) + "&token_type=" + tokenType + "&scope=" + scope, false).
                dontCache().
                body("").
                build());
        extractSessionFromRequestAndAddToResponse(exc);
        return Outcome.RETURN;
    }

    private boolean isAbsoluteUri(String givenRedirect_uri) {
        try {
            // Doing it this way as URIs scheme seems to be wrong
            String[] split = givenRedirect_uri.split("://");
            return split.length == 2;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isOAuth2RevokeCall(Exchange exc) {
        return exc.getRequestURI().startsWith("/oauth2/revoke");
    }

    private String hasGivenInvalidScopes(String givenScopes, String validScopes) {

        HashSet<String> valid = toSet(validScopes.split(" "));

        StringBuilder builder = new StringBuilder();

        for (String scope : toSet(givenScopes.split(" "))) {
            if (!valid.contains(scope))
                builder.append(scope).append(" ");
        }
        return builder.toString().trim();
    }

    private HashSet<String> toSet(String[] array) {
        HashSet<String> set = new HashSet<String>();
        for (String s : array)
            set.add(s);
        return set;
    }

    private String verifyScopes(String scopes) {
        String[] scopeList = scopes.split(" ");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < scopeList.length; i++) {
            if (this.scopeList.scopeExists(scopeList[i]))
                builder.append(scopeList[i]).append(" ");
        }
        return builder.toString().trim();
    }

    private boolean isOAuth2UserinfoCall(Exchange exc) {
        return exc.getRequestURI().startsWith("/oauth2/userinfo");
    }

    private Map<String, String> extractBody(Message msg) {
        String body = msg.getBodyAsStringDecoded();
        String[] split = body.split("&");
        HashMap<String, String> params = new HashMap<String, String>();
        for (String param : split) {
            String[] paramSplit = param.split("=");
            params.put(paramSplit[0], paramSplit[1]);
        }
        return params;
    }

    private boolean isOAuth2TokenCall(Exchange exc) {
        return exc.getRequestURI().startsWith("/oauth2/token");
    }

    private boolean isOAuth2AuthCall(Exchange exc) {
        return exc.getRequestURI().startsWith("/oauth2/auth");
    }

    private Outcome respondWithAuthorizationCodeAndRedirect(Exchange exc, String code) throws UnsupportedEncodingException {
        Session s = sessionManager.getSession(exc.getRequest());
        String state;
        String redirectUrl;
        synchronized (s) {
            state = s.getUserAttributes().get("state");
            redirectUrl = s.getUserAttributes().get("redirect_uri");
        }

        exc.setResponse(Response.
                redirect(redirectUrl + "?code=" + code + (state == null ? "" : "&state=" + state), false).
                dontCache().
                body("").
                build());
        extractSessionFromRequestAndAddToResponse(exc);
        return Outcome.RETURN;
    }

    private String generateAuthorizationCode() {
        return new BigInteger(130, random).toString(32);
    }

    public UserDataProvider getUserDataProvider() {
        return userDataProvider;
    }

    public Outcome redirectToLoginWithSession(Exchange exc, HeaderField session) throws MalformedURLException, UnsupportedEncodingException {
        exc.setResponse(Response.
                redirect(path, false).
                dontCache().
                body("").
                build());
        addSessionHeader(exc.getResponse(), session);
        return Outcome.RETURN;
    }

    public HeaderField extraxtSessionHeader(Message msg) {
        for (HeaderField h : msg.getHeader().getAllHeaderFields()) {
            if (h.getHeaderName().equals("Set-Cookie")) {
                return h;
            } else if (h.getHeaderName().equals("Cookie")) {
                h.setHeaderName(new HeaderName("Set-Cookie"));
                return h;
            }
        }
        throw new RuntimeException();
    }

    public String extractSessionId(HeaderField sessionHeader) {
        String[] split = sessionHeader.getValue().split(" ");
        for (String s : split) {
            if (s.startsWith("SESSIONID=")) {
                return s.substring(10);
            }
        }
        throw new RuntimeException();
    }

    public Message addSessionHeader(Message msg, HeaderField session) {
        msg.getHeader().add(session);
        return msg;
    }

    public void extractSessionFromRequestAndAddToResponse(Exchange exc) {
        addSessionHeader(exc.getResponse(), extraxtSessionHeader(exc.getRequest()));
    }

    @Required
    @MCChildElement(order = 1)
    public void setUserDataProvider(UserDataProvider userDataProvider) {
        this.userDataProvider = userDataProvider;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    @MCChildElement(order = 2)
    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }


    public String getLocation() {
        return location;
    }

    @Required
    @MCAttribute
    public void setLocation(String location) {
        this.location = location;
    }

    public String getPath() {
        return path;
    }

    @Required
    @MCAttribute
    public void setPath(String path) {
        this.path = path;
    }

    public String getMessage() {
        return message;
    }

    @MCAttribute
    public void setMessage(String message) {
        this.message = message;
    }

    public AccountBlocker getAccountBlocker() {
        return accountBlocker;
    }

    @MCChildElement(order = 3)
    public void setAccountBlocker(AccountBlocker accountBlocker) {
        this.accountBlocker = accountBlocker;
    }

    public boolean isExposeUserCredentialsToSession() {
        return exposeUserCredentialsToSession;
    }

    @MCAttribute
    public void setExposeUserCredentialsToSession(boolean exposeUserCredentialsToSession) {
        this.exposeUserCredentialsToSession = exposeUserCredentialsToSession;
    }

    public ClientList getClientList() {
        return clientList;
    }

    @Required
    @MCChildElement(order = 4)
    public void setClientList(ClientList clientList) {
        this.clientList = clientList;
    }

    public TokenGenerator getTokenGenerator() {
        return tokenGenerator;
    }

    @MCChildElement(order = 5)
    public void setTokenGenerator(TokenGenerator tokenGenerator) {
        this.tokenGenerator = tokenGenerator;
    }

    public ScopeList getScopeList() {
        return scopeList;
    }

    @MCChildElement(order = 6)
    public void setScopeList(ScopeList scopeList) {
        this.scopeList = scopeList;
    }
}
