/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.runtime;

import com.intuit.karate.AssignType;
import com.intuit.karate.FileUtils;
import com.intuit.karate.Logger;
import com.intuit.karate.StringUtils;
import com.intuit.karate.XmlUtils;
import com.intuit.karate.core.Embed;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.PerfEvent;
import com.intuit.karate.core.Plugin;
import com.intuit.karate.core.StepResult;
import com.intuit.karate.data.Json;
import com.intuit.karate.data.JsonUtils;
import com.intuit.karate.driver.Driver;
import com.intuit.karate.driver.DriverOptions;
import com.intuit.karate.exception.KarateException;
import com.intuit.karate.graal.JsEngine;
import com.intuit.karate.graal.JsFunction;
import com.intuit.karate.graal.JsValue;
import com.intuit.karate.match.Match;
import com.intuit.karate.match.MatchResult;
import com.intuit.karate.match.MatchType;
import com.intuit.karate.match.MatchValue;
import com.intuit.karate.netty.WebSocketClient;
import com.intuit.karate.netty.WebSocketOptions;
import com.intuit.karate.server.ArmeriaHttpClient;
import com.intuit.karate.server.HttpClient;
import com.intuit.karate.server.HttpConstants;
import com.intuit.karate.server.HttpRequest;
import com.intuit.karate.server.HttpRequestBuilder;
import com.intuit.karate.server.Request;
import com.intuit.karate.server.Response;
import com.intuit.karate.shell.Command;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Value;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author pthomas3
 */
public class ScenarioEngine {

    private static final String KARATE = "karate";
    private static final String READ = "read";

    public static final String RESPONSE = "response";
    public static final String RESPONSE_HEADERS = "responseHeaders";
    public static final String RESPONSE_STATUS = "responseStatus";
    private static final String RESPONSE_BYTES = "responseBytes";
    private static final String RESPONSE_COOKIES = "responseCookies";
    private static final String RESPONSE_TIME = "responseTime";
    private static final String RESPONSE_TYPE = "responseType";

    public static final String REQUEST = "request";
    public static final String REQUEST_URL_BASE = "requestUrlBase";
    public static final String REQUEST_URI = "requestUri";
    public static final String REQUEST_METHOD = "requestMethod";
    public static final String REQUEST_HEADERS = "requestHeaders";
    private static final String REQUEST_TIME_STAMP = "requestTimeStamp";

    public final ScenarioRuntime runtime;
    protected final ScenarioFileReader fileReader;
    public final Map<String, Variable> vars;
    public final Logger logger;

    private final Function<String, Object> readFunction;
    private final ScenarioBridge bridge;

    protected Map<String, Object> magicVariables;
    private boolean aborted;
    private Throwable failedReason;

    private JsEngine JS;

    // only used by mock server
    public ScenarioEngine(ScenarioRuntime runtime) {
        this(runtime.engine.config, runtime, runtime.engine.vars, runtime.logger);
    }

    public ScenarioEngine(Config config, ScenarioRuntime runtime, Map<String, Variable> vars, Logger logger) {
        this.config = config;
        this.runtime = runtime;
        fileReader = new ScenarioFileReader(this, runtime.featureRuntime);
        readFunction = s -> JsValue.fromJava(fileReader.readFile(s));
        bridge = new ScenarioBridge();
        this.vars = vars;
        this.logger = logger;
    }

    private List<ScenarioEngine> children;
    private ScenarioEngine parent;

    public ScenarioEngine child() {
        ScenarioEngine child = new ScenarioEngine(config, runtime, detachVariables(), logger);
        child.parent = this;
        if (children == null) {
            children = new ArrayList();
        }
        children.add(child);
        return child;
    }

    private static final ThreadLocal<ScenarioEngine> THREAD_LOCAL = new ThreadLocal<ScenarioEngine>();

    public static ScenarioEngine get() {
        return THREAD_LOCAL.get();
    }

    protected static void set(ScenarioEngine se) {
        THREAD_LOCAL.set(se);
    }

    protected static void remove() {
        THREAD_LOCAL.remove();
    }

    // engine ==================================================================
    //
    public boolean isAborted() {
        return aborted;
    }

    public void setAborted(boolean aborted) {
        this.aborted = aborted;
    }

    public boolean isFailed() {
        return failedReason != null;
    }

    public void setFailedReason(Throwable failedReason) {
        this.failedReason = failedReason;
    }

    public Throwable getFailedReason() {
        return failedReason;
    }

    public void call(boolean callOnce, String line) {
        call(callOnce, line, true);
    }

    public void assign(AssignType assignType, String name, String exp) {
        assign(assignType, name, exp, true);
    }

    public void matchResult(MatchType matchType, String expression, String path, String expected) {
        MatchResult mr = match(matchType, expression, path, expected);
        if (!mr.pass) {
            setFailedReason(new KarateException(mr.message));
        }
    }

    public void set(String name, String path, String exp) {
        set(name, path, exp, false, false);
    }

    public void set(String name, String path, List<Map<String, String>> table) {
        setViaTable(name, path, table);
    }

    public void remove(String name, String path) {
        set(name, path, null, true, false);
    }

    public void table(String name, List<Map<String, String>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, String> map : rows) {
            Map<String, Object> row = new LinkedHashMap<>(map);
            List<String> toRemove = new ArrayList(map.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String exp = (String) entry.getValue();
                Variable sv = evalKarateExpression(exp);
                if (sv.isNull() && !isWithinParentheses(exp)) { // by default empty / null will be stripped, force null like this: '(null)'
                    toRemove.add(entry.getKey());
                } else {
                    if (sv.isString()) {
                        entry.setValue(sv.getAsString());
                    } else { // map, list etc
                        entry.setValue(sv.getValue());
                    }
                }
            }
            for (String keyToRemove : toRemove) {
                row.remove(keyToRemove);
            }
            result.add(row);
        }
        setVariable(name.trim(), result);
    }

    public void replace(String name, String token, String value) {
        name = name.trim();
        String text = getVarAsString(name);
        String replaced = replacePlaceholderText(text, token, value);
        vars.put(name, new Variable(replaced));
    }

    public void replace(String name, List<Map<String, String>> table) {
        replaceTable(name, table);
    }

    public void assertTrue(String expression) {
        if (!evalJs(expression).isTrue()) {
            String message = "did not evaluate to 'true': " + expression;
            setFailedReason(new KarateException(message));
        }
    }

    public void print(List<String> exps) {
        if (!config.isPrintEnabled()) {
            return;
        }
        evalJs("karate.log('[print]'," + StringUtils.join(exps, ',') + ")");
    }

    public void invokeAfterHookIfConfigured(boolean afterFeature) {
        if (runtime.caller.depth > 0) {
            return;
        }
        Variable v = afterFeature ? config.getAfterFeature() : config.getAfterScenario();
        if (v.isJsOrJavaFunction()) {
            try {
                executeFunction(v);
            } catch (Exception e) {
                String prefix = afterFeature ? "afterFeature" : "afterScenario";
                logger.warn("{} hook failed: {}", prefix, e + "");
            }
        }
    }

    public void stop(StepResult lastStepResult) {
        if (runtime.caller.isSharedScope()) {
            ScenarioEngine parent = runtime.caller.parentRuntime.engine;
            if (driver != null) { // a called feature inited the driver
                parent.setDriver(driver);
            }
            if (robot != null) {
                parent.setRobot(robot);
            }
            parent.webSocketClients = webSocketClients;
            // return, don't kill driver just yet
        } else if (runtime.caller.depth == 0) { // end of top-level scenario (no caller)
            if (webSocketClients != null) {
                webSocketClients.forEach(WebSocketClient::close);
            }
            if (driver != null) { // TODO move this to Plugin.afterScenario()
                DriverOptions options = driver.getOptions();
                if (options.stop) {
                    driver.quit();
                }
                if (options.target != null) {
                    logger.debug("custom target configured, attempting stop()");
                    Map<String, Object> map = options.target.stop(logger);
                    String video = (String) map.get("video");
                    if (video != null && lastStepResult != null) {
                        Embed embed = Embed.forVideoFile(video);
                        lastStepResult.addEmbed(embed);
                    }
                } else {
                    if (options.afterStop != null) {
                        Command.execLine(null, options.afterStop);
                    }
                    if (options.videoFile != null) {
                        File src = new File(options.videoFile);
                        if (src.exists()) {
                            String path = FileUtils.getBuildDir() + File.separator + System.currentTimeMillis() + ".mp4";
                            File dest = new File(path);
                            FileUtils.copy(src, dest);
                            Embed embed = Embed.forVideoFile("../" + dest.getName());
                            lastStepResult.addEmbed(embed);
                            logger.debug("appended video to report: {}", dest.getPath());
                        }
                    }
                }
            }
            if (robot != null) {
                robot.afterScenario();
            }
        }
    }

    // gatling =================================================================
    //   
    private PerfEvent prevPerfEvent;

    public void logLastPerfEvent(String failureMessage) {
        if (prevPerfEvent != null && runtime.featureRuntime.isPerfMode()) {
            if (failureMessage != null) {
                prevPerfEvent.setFailed(true);
                prevPerfEvent.setMessage(failureMessage);
            }
            runtime.featureRuntime.getPerfRuntime().reportPerfEvent(prevPerfEvent);
        }
        prevPerfEvent = null;
    }

    public void capturePerfEvent(PerfEvent event) {
        logLastPerfEvent(null);
        prevPerfEvent = event;
    }

    // http ====================================================================
    //
    private HttpRequestBuilder http; // see init() method
    private HttpRequest request;
    private Response response;
    private Config config;

    public HttpRequest getPrevRequest() {
        return request == null ? null : request;
    }

    public void configure(String key, String exp) {
        Variable v = evalKarateExpression(exp);
        configure(key, v);
    }

    public void configure(String key, Variable v) {
        key = StringUtils.trimToEmpty(key);
        // if next line returns true, http-client (may) need re-building
        if (config.configure(key, v)) {
            if (http != null) {
                http.client.setConfig(config);
                http.client.configChanged(key);
            }
        }
    }

    public Config getConfig() {
        return config;
    }

    // important: use this to trigger client re-config
    // callonce routine is one example
    public void setConfig(Config config) {
        this.config = config;
        if (http != null) {
            http.client.setConfig(config);
            http.client.configChanged(null);
        }
    }

    private String evalAsString(String exp) {
        return evalJs(exp).getAsString();
    }

    private void evalAsMap(String exp, BiConsumer<String, List<String>> fun) {
        Variable var = evalKarateExpression(exp);
        if (!var.isMap()) {
            logger.warn("did not evaluate to map {}: {}", exp, var);
            return;
        }
        Map<String, Object> map = var.getValue();
        map.forEach((k, v) -> {
            if (v instanceof List) {
                List list = (List) v;
                List<String> values = new ArrayList(list.size());
                for (Object o : list) { // support non-string values, e.g. numbers
                    if (o != null) {
                        values.add(o.toString());
                    }
                }
                fun.accept(k, values);
            } else if (v != null) {
                fun.accept(k, Collections.singletonList(v.toString()));
            }
        });
    }

    public void url(String exp) {
        http.url(evalAsString(exp));
    }

    public void path(List<String> paths) {
        for (String path : paths) {
            http.path(evalAsString(path));
        }
    }

    public void param(String name, List<String> exps) {
        List<String> values = new ArrayList(exps.size());
        if (exps.size() > 1) {
            String first = exps.get(0);
            if (first.startsWith("'") || first.startsWith("\"")) {
                // special case, commas within string
                String temp = StringUtils.join(exps, ',');
                exps = Collections.singletonList(temp);
            }
        }
        for (String exp : exps) {
            values.add(evalAsString(exp));
        }
        http.param(name, values);
    }

    public void params(String expr) {
        evalAsMap(expr, (k, v) -> http.param(k, v));
    }

    public void header(String name, List<String> exps) {
        List<String> values = new ArrayList(exps.size());
        for (String exp : exps) {
            values.add(evalAsString(exp));
        }
        http.header(name, values);
    }

    public void headers(String expr) {
        evalAsMap(expr, (k, v) -> http.header(k, v));
    }

    public void cookie(String name, String exp) {
        Variable var = evalKarateExpression(exp);
        if (var.isString()) {
            http.cookie(name, var.getAsString());
        } else if (var.isMap()) {
            Map<String, Object> map = var.getValue();
            map.put("name", name);
            http.cookie(map);
        }
    }

    // TODO document new options, [name = map | cookies listOfMaps]
    public void cookies(String exp) {
        Variable var = evalKarateExpression(exp);
        if (var.isMap()) {
            Map<String, Object> map = var.getValue();
            map.forEach((k, v) -> {
                if (v instanceof String) {
                    http.cookie(k, (String) v);
                } else if (v instanceof Map) {
                    Map<String, Object> temp = (Map) v;
                    temp.put("name", k);
                    http.cookie(map);
                }
            });
        } else if (var.isList()) {
            List<Map> list = var.getValue();
            list.forEach(map -> http.cookie(map));
        } else {
            logger.warn("did not evaluate to map or list {}: {}", exp, var);
        }
    }

    public void formField(String name, List<String> exps) {
        for (String exp : exps) {
            http.formField(name, evalKarateExpression(exp).getValue());
        }
    }

    public void formFields(String exp) {
        Variable var = evalKarateExpression(exp);
        if (var.isMap()) {
            Map<String, Object> map = var.getValue();
            map.forEach((k, v) -> {
                http.formField(k, v);
            });
        } else {
            logger.warn("did not evaluate to map {}: {}", exp, var);
        }
    }

    public void multipartField(String name, String value) {
        multipartFile(name, value);
    }

    public void multipartFields(String exp) {
        multipartFiles(exp);
    }

    private void multipart(String name, Object value) {
        Map<String, Object> map = new HashMap();
        map.put("name", name);
        if (value instanceof String) {
            map.put("value", (String) value);
            http.multiPart(map);
        } else if (value instanceof Map) {
            map.putAll((Map) value);
            http.multiPart(map);
        } else if (value instanceof List) {
            List list = (List) value;
            for (Object o : list) {
                if (o instanceof Map) {
                    map.putAll((Map) o);
                } else {
                    map.put("value", o);
                }
                http.multiPart(map);
            }
        } else {
            logger.warn("did not evaluate to string, map or list {}: {}", name, value);
        }
    }

    public void multipartFile(String name, String exp) {
        Variable var = evalKarateExpression(exp);
        multipart(name, var.getValue());
    }

    public void multipartFiles(String exp) {
        Variable var = evalKarateExpression(exp);
        if (var.isMap()) {
            Map<String, Object> map = var.getValue();
            map.forEach((k, v) -> multipart(k, v));
        } else if (var.isList()) {
            List<Map> list = var.getValue();
            for (Map map : list) {
                http.multiPart(map);
            }
        } else {
            logger.warn("did not evaluate to map or list {}: {}", exp, var);
        }
    }

    public void request(String body) {
        Variable v = evalKarateExpression(body);
        http.body(v.getValue());
    }

    public void soapAction(String exp) {
        String action = evalKarateExpression(exp).getAsString();
        if (action == null) {
            action = "";
        }
        http.header("SOAPAction", action);
        http.contentType("text/xml");
        method("POST");
    }

    public void retry(String condition) {
        http.setRetryUntil(condition);
    }

    public void method(String method) {
        if (!HttpConstants.HTTP_METHODS.contains(method.toUpperCase())) { // support expressions also
            method = evalKarateExpression(method).getAsString();
        }
        http.method(method);
        httpInvoke();
    }

    // extracted for mock proceed()
    private void httpInvoke() {
        if (http.isRetry()) {
            httpInvokeWithRetries();
        } else {
            httpInvokeOnce();
        }
        http.reset();
    }

    private void httpInvokeOnce() {
        Map<String, Map> cookies = getOrEvalAsMap(config.getCookies());
        if (cookies != null) {
            http.cookies(cookies.values());
        }
        Map<String, Object> headers = getOrEvalAsMap(config.getHeaders());
        if (headers != null) {
            http.headers(headers);
        }
        request = http.build();
        String perfEventName = null; // acts as a flag to report perf if not null
        if (runtime.featureRuntime.isPerfMode()) {
            perfEventName = runtime.featureRuntime.getPerfRuntime().getPerfEventName(request, runtime);
        }
        long startTime = System.currentTimeMillis();
        request.setStartTimeMillis(startTime);
        try {
            response = http.client.invoke(request);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            long responseTime = endTime - startTime;
            String message = "http call failed after " + responseTime + " milliseconds for url: " + request.getUrl();
            logger.error(e.getMessage() + ", " + message);
            if (perfEventName != null) {
                PerfEvent pe = new PerfEvent(startTime, endTime, perfEventName, 0);
                capturePerfEvent(pe); // failure flag and message should be set by logLastPerfEvent()
            }
            throw new KarateException(message, e);
        }
        byte[] bytes = response.getBody();
        Object body;
        String responseType;
        try {
            body = JsValue.fromBytes(bytes);
        } catch (Exception e) {
            body = FileUtils.toString(bytes);
            logger.warn("auto-conversion of response failed: {}", e.getMessage());
        }
        if (body instanceof Map || body instanceof List) {
            responseType = "json";
        } else if (body instanceof Node) {
            responseType = "xml";
        } else {
            responseType = "string";
        }
        setVariable(RESPONSE_STATUS, response.getStatus());
        setVariable(RESPONSE, body);
        setVariable(RESPONSE_BYTES, bytes);
        setVariable(RESPONSE_TYPE, responseType);
        setVariable(RESPONSE_HEADERS, response.getHeaders());
        cookies = response.getCookies();
        updateConfigCookies(cookies);
        setVariable(RESPONSE_COOKIES, cookies);
        startTime = request.getStartTimeMillis(); // in case it was re-adjusted by http client
        long endTime = request.getEndTimeMillis();
        setVariable(REQUEST_TIME_STAMP, startTime);
        setVariable(RESPONSE_TIME, endTime - startTime);
        if (perfEventName != null) {
            PerfEvent pe = new PerfEvent(startTime, endTime, perfEventName, response.getStatus());
            capturePerfEvent(pe);
        }
    }

    private void httpInvokeWithRetries() {
        int maxRetries = config.getRetryCount();
        int sleep = config.getRetryInterval();
        int retryCount = 0;
        while (true) {
            if (retryCount == maxRetries) {
                throw new KarateException("too many retry attempts: " + maxRetries);
            }
            if (retryCount > 0) {
                try {
                    logger.debug("sleeping before retry #{}", retryCount);
                    Thread.sleep(sleep);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            httpInvokeOnce();
            Variable v;
            try {
                v = evalKarateExpression(http.getRetryUntil());
            } catch (Exception e) {
                logger.warn("retry condition evaluation failed: {}", e.getMessage());
                v = Variable.NULL;
            }
            if (v.isTrue()) {
                if (retryCount > 0) {
                    logger.debug("retry condition satisfied");
                }
                break;
            } else {
                logger.debug("retry condition not satisfied: {}", http.getRetryUntil());
            }
            retryCount++;
        }
    }

    public void status(int status) {
        if (status != response.getStatus()) {
            String rawResponse = response.getBodyAsString();
            String responseTime = vars.get(RESPONSE_TIME).getAsString();
            String message = "status code was: " + response.getStatus() + ", expected: " + status
                    + ", response time: " + responseTime + ", url: " + request.getUrl()
                    + ", response: \n" + rawResponse;
            setFailedReason(new KarateException(message));
        }
    }

    private void updateConfigCookies(Map<String, Map> cookies) {
        if (cookies == null) {
            return;
        }
        if (config.getCookies().isNull()) {
            config.setCookies(new Variable(cookies));
        } else {
            Map<String, Object> map = getOrEvalAsMap(config.getCookies());
            map.putAll(cookies);
            config.setCookies(new Variable(map));
        }
    }

    // http mock ===============================================================
    //
    public void proceed(String requestUrlBase) {
        String urlBase = requestUrlBase == null ? vars.get(REQUEST_URL_BASE).getValue() : requestUrlBase;
        String uri = vars.get(REQUEST_URI).getValue();
        String url = uri == null ? urlBase : urlBase + "/" + uri;
        http.url(url);
        http.method(vars.get(REQUEST_METHOD).getValue());
        http.headers(vars.get(REQUEST_HEADERS).<Map>getValue());
        http.removeHeader(HttpConstants.HDR_CONTENT_LENGTH);
        http.body(vars.get(REQUEST).getValue());
        if (http.client instanceof ArmeriaHttpClient) {
            Request mockRequest = MockHandler.LOCAL_REQUEST.get();
            if (mockRequest != null) {
                ArmeriaHttpClient client = (ArmeriaHttpClient) http.client;
                client.setRequestContext(mockRequest.getRequestContext());
            }
        }
        httpInvoke();
    }

    // websocket / async =======================================================
    //   
    private List<WebSocketClient> webSocketClients;
    private Object signalResult;
    private final Object LOCK = new Object();

    public WebSocketClient webSocket(WebSocketOptions options) {
        WebSocketClient webSocketClient = new WebSocketClient(options, logger);
        if (webSocketClients == null) {
            webSocketClients = new ArrayList();
        }
        webSocketClients.add(webSocketClient);
        return webSocketClient;
    }

    public void signal(Object result) {
        logger.trace("signal called: {}", result);
        synchronized (LOCK) {
            signalResult = result;
            LOCK.notify();
        }
        if (parent != null) {
            parent.signal(result);
        }
    }

    public Object listen(long timeout, Runnable runnable) {
        if (runnable != null) {
            logger.trace("submitting listen function");
            new Thread(runnable).start();
        }
        synchronized (LOCK) {
            if (signalResult != null) {
                logger.debug("signal arrived early ! result: {}", signalResult);
                Object temp = signalResult;
                signalResult = null;
                return temp;
            }
            try {
                logger.trace("entered listen wait state");
                LOCK.wait(timeout);
                logger.trace("exit listen wait state, result: {}", signalResult);
            } catch (InterruptedException e) {
                logger.error("listen timed out: {}", e.getMessage());
            }
            Object temp = signalResult;
            signalResult = null;
            return temp;
        }
    }

    // ui driver / robot =======================================================
    //
    private Driver driver;
    private Plugin robot;

    public void driver(String expression) {

    }

    public void robot(String expression) {

    }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    public void setRobot(Plugin robot) {
        this.robot = robot;
    }

    //==========================================================================        
    //       
    protected void init() { // not in constructor because it has to be on Runnable.run() thread 
        JS = JsEngine.local();
        logger.trace("js context: {}", JS);
        attachVariables();
        setHiddenVariable(KARATE, bridge);
        setHiddenVariable(READ, readFunction);
        setVariables(magicVariables);
        HttpClient client = config.getClientFactory().apply(this);
        http = new HttpRequestBuilder(client);
    }

    protected boolean isInited() {
        return JS != null;
    }

    private void attachVariables() {
        vars.forEach((k, v) -> {
            switch (v.type) {
                case JS_FUNCTION:
                    Value value = attach(v.getValue());
                    v = new Variable(value);
                    vars.put(k, v);
                    break;
                case MAP:
                case LIST:
                    recurseAndAttach(v.getValue());
                    break;
                case OTHER:
                    if (v.isJsFunctionWrapper()) {
                        JsFunction jf = v.getValue();
                        Value attached = attachSource(jf.source);
                        v = new Variable(attached);
                        vars.put(k, v);
                    }
                    break;
                default:
                // do nothing
            }
            JS.put(k, v.getValue());
        });
    }

    private Map<String, Variable> detachVariables() {
        Map<String, Variable> detached = new HashMap(vars.size());
        vars.forEach((k, v) -> {
            switch (v.type) {
                case JS_FUNCTION:
                    JsFunction jf = new JsFunction(v.getValue());
                    v = new Variable(jf);
                    break;
                case MAP:
                case LIST:
                    recurseAndDetach(v.getValue());
                    break;
                default:
                // do nothing
            }
            detached.put(k, v);
        });
        return detached;
    }

    private Object recurseAndAttach(Object o) {
        if (o instanceof Value) {
            Value value = (Value) o;
            return value.canExecute() ? attach(value) : null;
        } else if (o instanceof JsFunction) {
            JsFunction jf = (JsFunction) o;
            return attachSource(jf.source);
        } else if (o instanceof List) {
            List list = (List) o;
            int count = list.size();
            for (int i = 0; i < count; i++) {
                Object child = list.get(i);
                Object result = recurseAndAttach(child);
                if (result != null) {
                    list.set(i, result);
                }
            }
            return null;
        } else if (o instanceof Map) {
            Map<String, Object> map = (Map) o;
            map.forEach((k, v) -> {
                Object result = recurseAndAttach(v);
                if (result != null) {
                    map.put(k, result);
                }
            });
            return null;
        } else {
            return null;
        }
    }

    private Object recurseAndDetach(Object o) {
        if (o instanceof Value) {
            Value value = (Value) o;
            return value.canExecute() ? new JsFunction(value) : null;
        } else if (o instanceof List) {
            List list = (List) o;
            int count = list.size();
            for (int i = 0; i < count; i++) {
                Object child = list.get(i);
                Object result = recurseAndDetach(child);
                if (result != null) {
                    list.set(i, result);
                }
            }
            return null;
        } else if (o instanceof Map) {
            Map<String, Object> map = (Map) o;
            map.forEach((k, v) -> {
                Object result = recurseAndDetach(v);
                if (result != null) {
                    map.put(k, result);
                }
            });
            return null;
        } else {
            return null;
        }
    }

    public Value attachSource(CharSequence source) {
        Value value = JS.evalForValue("(" + source + ")");
        return attach(value);
    }

    public Value attach(Value before) {
        return JS.attach(before);
    }

    public JsValue executeJsValue(Value function, Object... args) {
        return JS.execute(function, args);
    }

    protected <T> Map<String, T> getOrEvalAsMap(Variable var) {
        if (var.isJsOrJavaFunction()) {
            Variable res = executeFunction(var);
            return res.isMap() ? res.getValue() : null;
        } else {
            return var.isMap() ? var.getValue() : null;
        }
    }

    public Variable executeFunction(Variable var, Object... args) {
        switch (var.type) {
            case JS_FUNCTION:
                Value jsFunction = var.getValue();
                JsValue jsResult = JS.execute(jsFunction, args);
                return new Variable(jsResult);
            case JAVA_FUNCTION:  // definitely a "call" with a single argument
                Function javaFunction = var.getValue();
                Object arg = args.length == 0 ? null : args[0];
                Object javaResult = javaFunction.apply(arg);
                return new Variable(JsValue.unWrap(javaResult));
            default:
                throw new RuntimeException("expected function, but was: " + var);
        }
    }

    public Variable evalJs(String js) {
        try {
            return new Variable(JS.eval(js));
        } catch (Exception e) {
            KarateException ke = fromJsEvalException(js, e);
            setFailedReason(ke);
            throw ke;
        }
    }

    protected static KarateException fromJsEvalException(String js, Exception e) {
        // do our best to make js error traces informative, else thrown exception seems to
        // get swallowed by the java reflection based method invoke flow
        StackTraceElement[] stack = e.getStackTrace();
        StringBuilder sb = new StringBuilder();
        sb.append(">>>> js failed:\n");
        List<String> lines = StringUtils.toStringLines(js);
        int index = 0;
        for (String line : lines) {
            sb.append(String.format("%02d", ++index)).append(": ").append(line).append('\n');
        }
        sb.append("<<<<\n");
        sb.append(e.toString()).append('\n');
        for (int i = 0; i < stack.length; i++) {
            String line = stack[i].toString();
            sb.append("- ").append(line).append('\n');
            if (line.startsWith("<js>")) {
                break;
            }
        }
        return new KarateException(sb.toString());
    }

    protected void setHiddenVariable(String key, Object value) {
        if (value instanceof Variable) {
            value = ((Variable) value).getValue();
        }
        JS.put(key, value);
    }

    public void setVariable(String key, Object value) {
        Variable v;
        if (value instanceof Variable) {
            v = (Variable) value;
        } else {
            v = new Variable(value);
        }
        vars.put(key, v);
        if (JS != null) {
            JS.put(key, v.getValue());
        }
        if (children != null) {
            children.forEach(c -> c.setVariable(key, value));
        }
    }

    public void setVariables(Map<String, Object> map) {
        if (map == null) {
            return;
        }
        map.forEach((k, v) -> setVariable(k, v));
    }

    private static Map<String, Variable> copy(Map<String, Variable> source, boolean deep) {
        Map<String, Variable> map = new HashMap(source.size());
        source.forEach((k, v) -> map.put(k, v.copy(deep)));
        return map;
    }

    public Map<String, Variable> copyVariables(boolean deep) {
        return copy(vars, deep);
    }

    public Map<String, Object> getAllVariablesAsMap() {
        Map<String, Object> map = new HashMap(vars.size());
        vars.forEach((k, v) -> map.put(k, v == null ? null : v.getValue()));
        return map;
    }

    private static void validateVariableName(String name) {
        if (!isValidVariableName(name)) {
            throw new RuntimeException("invalid variable name: " + name);
        }
        if (KARATE.equals(name)) {
            throw new RuntimeException("'karate' is a reserved name");
        }
        if (REQUEST.equals(name) || "url".equals(name)) {
            throw new RuntimeException("'" + name + "' is a reserved name, also use the form '* " + name + " <expression>' instead");
        }
    }

    private Variable evalAndCastTo(AssignType assignType, String exp) {
        Variable v = evalKarateExpression(exp);
        switch (assignType) {
            case BYTE_ARRAY:
                return new Variable(v.getAsByteArray());
            case STRING:
                return new Variable(v.getAsString());
            case XML:
                return new Variable(v.getAsXml());
            case XML_STRING:
                String xml = XmlUtils.toString(v.getAsXml());
                return new Variable(xml);
            case JSON:
                return new Variable(v.getValueAndForceParsingAsJson());
            case YAML:
                return new Variable(JsonUtils.fromYaml(v.getAsString()));
            case CSV:
                return new Variable(JsonUtils.fromCsv(v.getAsString()));
            case COPY:
                return v.copy(true);
            default: // AUTO (TEXT is pre-handled)
                return v; // as is
        }
    }

    public void assign(AssignType assignType, String name, String exp, boolean validateName) {
        name = StringUtils.trimToEmpty(name);
        if (validateName) {
            validateVariableName(name);
            if (vars.containsKey(name)) {
                logger.warn("over-writing existing variable '{}' with new value: {}", name, exp);
            }
        }
        if (assignType == AssignType.TEXT) {
            setVariable(name, exp);
        } else {
            setVariable(name, evalAndCastTo(assignType, exp));
        }
    }

    private static boolean isEmbeddedExpression(String text) {
        return text != null && (text.startsWith("#(") || text.startsWith("##(")) && text.endsWith(")");
    }

    private static class EmbedAction {

        final boolean remove;
        final Object value;

        private EmbedAction(boolean remove, Object value) {
            this.remove = remove;
            this.value = value;
        }

        static EmbedAction remove() {
            return new EmbedAction(true, null);
        }

        static EmbedAction update(Object value) {
            return new EmbedAction(false, value);
        }

    }

    public Variable evalEmbeddedExpressions(Variable value) {
        switch (value.type) {
            case STRING:
            case MAP:
            case LIST:
                EmbedAction ea = recurseEmbeddedExpressions(value);
                if (ea != null) {
                    return ea.remove ? Variable.NULL : new Variable(ea.value);
                } else {
                    return value;
                }
            case XML:
                recurseXmlEmbeddedExpressions(value.getValue());
            default:
                return value;
        }
    }

    private EmbedAction recurseEmbeddedExpressions(Variable node) {
        switch (node.type) {
            case LIST:
                List list = node.getValue();
                Set<Integer> indexesToRemove = new HashSet();
                int count = list.size();
                for (int i = 0; i < count; i++) {
                    EmbedAction ea = recurseEmbeddedExpressions(new Variable(list.get(i)));
                    if (ea != null) {
                        if (ea.remove) {
                            indexesToRemove.add(i);
                        } else {
                            list.set(i, ea.value);
                        }
                    }
                }
                if (!indexesToRemove.isEmpty()) {
                    List copy = new ArrayList(count - indexesToRemove.size());
                    for (int i = 0; i < count; i++) {
                        if (!indexesToRemove.contains(i)) {
                            copy.add(list.get(i));
                        }
                    }
                    return EmbedAction.update(copy);
                } else {
                    return null;
                }
            case MAP:
                Map<String, Object> map = node.getValue();
                List<String> keysToRemove = new ArrayList();
                map.forEach((k, v) -> {
                    EmbedAction ea = recurseEmbeddedExpressions(new Variable(v));
                    if (ea != null) {
                        if (ea.remove) {
                            keysToRemove.add(k);
                        } else {
                            map.put(k, ea.value);
                        }
                    }
                });
                for (String key : keysToRemove) {
                    map.remove(key);
                }
                return null;
            case XML:
                return null;
            case STRING:
                String value = StringUtils.trimToNull(node.getValue());
                if (!isEmbeddedExpression(value)) {
                    return null;
                }
                boolean optional = value.charAt(1) == '#';
                value = value.substring(optional ? 2 : 1);
                try {
                    JsValue result = JS.eval(value);
                    if (optional) {
                        if (result.isNull()) {
                            return EmbedAction.remove();
                        } else if (result.isObject() || result.isArray()) {
                            // preserve optional JSON chunk schema-like references as-is, they are needed for future match attempts
                            // TODO similar XML schema intelligence
                            return null;
                        }
                        // and only substitute primitives ! 
                    }
                    return EmbedAction.update(result.getValue());
                } catch (Exception e) {
                    logger.trace("embedded expression failed {}: {}", value, e.getMessage());
                    return null;
                }
            default:
                // do nothing
                return null;
        }
    }

    private void recurseXmlEmbeddedExpressions(Node node) {
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            node = node.getFirstChild();
        }
        NamedNodeMap attribs = node.getAttributes();
        int attribCount = attribs == null ? 0 : attribs.getLength();
        Set<Attr> attributesToRemove = new HashSet(attribCount);
        for (int i = 0; i < attribCount; i++) {
            Attr attrib = (Attr) attribs.item(i);
            String value = attrib.getValue();
            value = StringUtils.trimToNull(value);
            if (isEmbeddedExpression(value)) {
                boolean optional = value.charAt(1) == '#';
                value = value.substring(optional ? 2 : 1);
                try {
                    JsValue jv = JS.eval(value);
                    if (optional && jv.isNull()) {
                        attributesToRemove.add(attrib);
                    } else {
                        attrib.setValue(jv.getAsString());
                    }
                } catch (Exception e) {
                    logger.trace("xml-attribute embedded expression failed, {}: {}", attrib.getName(), e.getMessage());
                }
            }
        }
        for (Attr toRemove : attributesToRemove) {
            attribs.removeNamedItem(toRemove.getName());
        }
        NodeList nodeList = node.getChildNodes();
        int childCount = nodeList.getLength();
        List<Node> nodes = new ArrayList(childCount);
        for (int i = 0; i < childCount; i++) {
            nodes.add(nodeList.item(i));
        }
        Set<Node> elementsToRemove = new HashSet(childCount);
        for (Node child : nodes) {
            String value = child.getNodeValue();
            if (value != null) {
                value = StringUtils.trimToEmpty(value);
                if (isEmbeddedExpression(value)) {
                    boolean optional = value.charAt(1) == '#';
                    value = value.substring(optional ? 2 : 1);
                    try {
                        JsValue jv = JS.eval(value);
                        if (optional && jv.isNull()) {
                            elementsToRemove.add(child);
                        } else {
                            if (jv.isXml() || jv.isObject()) {
                                Node evalNode = jv.isXml() ? jv.getValue() : XmlUtils.fromMap(jv.getValue());
                                if (evalNode.getNodeType() == Node.DOCUMENT_NODE) {
                                    evalNode = evalNode.getFirstChild();
                                }
                                if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
                                    child.setNodeValue(XmlUtils.toString(evalNode));
                                } else {
                                    evalNode = node.getOwnerDocument().importNode(evalNode, true);
                                    child.getParentNode().replaceChild(evalNode, child);
                                }
                            } else {
                                child.setNodeValue(jv.getAsString());
                            }
                        }
                    } catch (Exception e) {
                        logger.trace("xml embedded expression failed, {}: {}", child.getNodeName(), e.getMessage());
                    }
                }
            } else if (child.hasChildNodes() || child.hasAttributes()) {
                recurseXmlEmbeddedExpressions(child);
            }
        }
        for (Node toRemove : elementsToRemove) { // because of how the above routine works, these are always of type TEXT_NODE
            Node parent = toRemove.getParentNode(); // element containing the text-node
            Node grandParent = parent.getParentNode(); // parent element
            grandParent.removeChild(parent);
        }
    }

    private String getVarAsString(String name) {
        Variable v = vars.get(name);
        if (v == null) {
            throw new RuntimeException("no variable found with name: " + name);
        }
        return v.getAsString();
    }

    public String replacePlaceholderText(String text, String token, String replaceWith) {
        if (text == null) {
            return null;
        }
        replaceWith = StringUtils.trimToNull(replaceWith);
        if (replaceWith == null) {
            return text;
        }
        try {
            Variable v = evalKarateExpression(replaceWith);
            replaceWith = v.getAsString();
        } catch (Exception e) {
            throw new RuntimeException("expression error (replace string values need to be within quotes): " + e.getMessage());
        }
        if (replaceWith == null) { // ignore if eval result is null
            return text;
        }
        token = StringUtils.trimToNull(token);
        if (token == null) {
            return text;
        }
        char firstChar = token.charAt(0);
        if (Character.isLetterOrDigit(firstChar)) {
            token = '<' + token + '>';
        }
        return text.replace(token, replaceWith);
    }

    private static final String TOKEN = "token";

    public void replaceTable(String text, List<Map<String, String>> list) {
        if (text == null) {
            return;
        }
        if (list == null) {
            return;
        }
        for (Map<String, String> map : list) {
            String token = map.get(TOKEN);
            if (token == null) {
                continue;
            }
            // the verbosity below is to be lenient with table second column name
            List<String> keys = new ArrayList(map.keySet());
            keys.remove(TOKEN);
            Iterator<String> iterator = keys.iterator();
            if (iterator.hasNext()) {
                String key = keys.iterator().next();
                String value = map.get(key);
                replace(text, token, value);
            }
        }

    }

    public void set(String name, String path, Variable value) {
        set(name, path, false, value, false, false);
    }

    private void set(String name, String path, String exp, boolean delete, boolean viaTable) {
        set(name, path, isWithinParentheses(exp), evalKarateExpression(exp), delete, viaTable);
    }

    private void set(String name, String path, boolean isWithinParentheses, Variable value, boolean delete, boolean viaTable) {
        name = StringUtils.trimToEmpty(name);
        path = StringUtils.trimToNull(path);
        if (viaTable && value.isNull() && !isWithinParentheses) {
            // by default, skip any expression that evaluates to null unless the user expressed
            // intent to over-ride by enclosing the expression in parentheses
            return;
        }
        if (path == null) {
            StringUtils.Pair nameAndPath = parseVariableAndPath(name);
            name = nameAndPath.left;
            path = nameAndPath.right;
        }
        Variable target = vars.get(name);
        if (isDollarPrefixedJsonPath(path)) {
            if (target == null || target.isNull()) {
                if (viaTable) { // auto create if using set via cucumber table as a convenience
                    Json json;
                    if (path.startsWith("$[") && !path.startsWith("$['")) {
                        json = new Json("[]");
                    } else {
                        json = new Json("{}");
                    }
                    target = new Variable(json.asMapOrList());
                    setVariable(name, target);
                } else {
                    throw new RuntimeException("variable is null or not set '" + name + "'");
                }
            }
            Json json;
            if (target.isMap()) {
                json = new Json(target.<Map>getValue());
            } else if (target.isList()) {
                json = new Json(target.<List>getValue());
            } else {
                throw new RuntimeException("cannot set json path on type: " + target);
            }
            if (delete) {
                json.remove(path);
            } else {
                json.set(path, value.<Object>getValue());
            }
        } else if (isXmlPath(path)) {
            if (target == null || target.isNull()) {
                if (viaTable) { // auto create if using set via cucumber table as a convenience
                    Document empty = XmlUtils.newDocument();
                    target = new Variable(empty);
                    setVariable(name, target);
                } else {
                    throw new RuntimeException("variable is null or not set '" + name + "'");
                }
            }
            Document doc = target.getValue();
            if (delete) {
                XmlUtils.removeByPath(doc, path);
            } else if (value.isXml()) {
                Node node = value.getValue();
                XmlUtils.setByPath(doc, path, node);
            } else if (value.isMap()) { // cast to xml
                Node node = XmlUtils.fromMap(value.getValue());
                XmlUtils.setByPath(doc, path, node);
            } else {
                XmlUtils.setByPath(doc, path, value.getAsString());
            }
        } else {
            throw new RuntimeException("unexpected path: " + path);
        }

    }

    private static final String PATH = "path";

    public void setViaTable(String name, String path, List<Map<String, String>> list) {
        name = StringUtils.trimToEmpty(name);
        path = StringUtils.trimToNull(path);
        if (path == null) {
            StringUtils.Pair nameAndPath = parseVariableAndPath(name);
            name = nameAndPath.left;
            path = nameAndPath.right;
        }
        for (Map<String, String> map : list) {
            String append = (String) map.get(PATH);
            if (append == null) {
                continue;
            }
            List<String> keys = new ArrayList(map.keySet());
            keys.remove(PATH);
            int columnCount = keys.size();
            for (int i = 0; i < columnCount; i++) {
                String key = keys.get(i);
                String expression = StringUtils.trimToNull(map.get(key));
                if (expression == null) { // cucumber cell was left blank
                    continue; // skip
                    // default behavior is to skip nulls when the expression evaluates 
                    // this is driven by the routine in setValueByPath
                    // and users can over-ride this by simply enclosing the expression in parentheses
                }
                String suffix;
                try {
                    int arrayIndex = Integer.valueOf(key);
                    suffix = "[" + arrayIndex + "]";
                } catch (NumberFormatException e) { // default to the column position as the index
                    suffix = columnCount > 1 ? "[" + i + "]" : "";
                }
                String finalPath;
                if (append.startsWith("/") || (path != null && path.startsWith("/"))) { // XML
                    if (path == null) {
                        finalPath = append + suffix;
                    } else {
                        finalPath = path + suffix + '/' + append;
                    }
                } else {
                    if (path == null) {
                        path = "$";
                    }
                    finalPath = path + suffix + '.' + append;
                }
                set(name, finalPath, expression, false, true);
            }
        }
    }

    public static StringUtils.Pair parseVariableAndPath(String text) {
        Matcher matcher = VAR_AND_PATH_PATTERN.matcher(text);
        matcher.find();
        String name = text.substring(0, matcher.end());
        String path;
        if (matcher.end() == text.length()) {
            path = "";
        } else {
            path = text.substring(matcher.end()).trim();
        }
        if (isXmlPath(path) || isXmlPathFunction(path)) {
            // xml, don't prefix for json
        } else {
            path = "$" + path;
        }
        return StringUtils.pair(name, path);
    }

    public MatchResult match(MatchType matchType, String expression, String path, String rhs) {
        String name = StringUtils.trimToEmpty(expression);
        if (isDollarPrefixedJsonPath(name) || isXmlPath(name)) { // 
            path = name;
            name = RESPONSE;
        }
        if (name.startsWith("$")) { // in case someone used the dollar prefix by mistake on the LHS
            name = name.substring(1);
        }
        path = StringUtils.trimToNull(path);
        if (path == null) {
            StringUtils.Pair pair = parseVariableAndPath(name);
            name = pair.left;
            path = pair.right;
        }
        if ("header".equals(name)) { // convenience shortcut for asserting against response header
            return match(matchType, RESPONSE_HEADERS, "$['" + path + "'][0]", rhs);
        }
        Variable actual;
        // karate started out by "defaulting" to JsonPath on the LHS of a match so we have this kludge
        // but we now handle JS expressions of almost any shape on the LHS, if in doubt, wrap in parentheses
        // actually it is not too bad - the XPath function check is the only odd one out
        // rules:
        // if not XPath function, wrapped in parentheses, involves function call
        //      [then] JS eval
        // else if XPath, JsonPath, JsonPath wildcard ".." or "*" or "[?"
        //      [then] eval name, and do a JsonPath or XPath using the parsed path
        if (isXmlPathFunction(path)
                || (!name.startsWith("(") && !path.endsWith(")") && !path.contains(")."))
                && (isDollarPrefixed(path) || isJsonPath(path) || isXmlPath(path))) {
            actual = evalKarateExpression(name);
            // edge case: java property getter, e.g. "driver.cookies"
            if (!actual.isMap() && !actual.isList() && !isXmlPath(path) && !isXmlPathFunction(path)) {
                actual = evalKarateExpression(expression); // fall back to JS eval of entire LHS
                path = "$";
            }
        } else {
            actual = evalKarateExpression(expression); // JS eval of entire LHS
            path = "$";
        }
        if ("$".equals(path) || "/".equals(path)) {
            // we have eval-ed the entire LHS, so proceed to match RHS to "$"
        } else {
            if (isDollarPrefixed(path)) { // json-path
                actual = evalJsonPath(actual, path);
            } else { // xpath
                actual = evalXmlPath(actual, path);
            }
        }
        Variable expected = evalKarateExpression(rhs);
        return match(matchType, actual.getValue(), expected.getValue());
    }

    public MatchResult match(MatchType matchType, Object actual, Object expected) {
        return Match.execute(JS, matchType, new MatchValue(actual), new MatchValue(expected));
    }

    private static final Pattern VAR_AND_PATH_PATTERN = Pattern.compile("\\w+");
    private static final String VARIABLE_PATTERN_STRING = "[a-zA-Z][\\w]*";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(VARIABLE_PATTERN_STRING);
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("^function[^(]*\\(");

    public static boolean isJavaScriptFunction(String text) {
        return FUNCTION_PATTERN.matcher(text).find();
    }

    public static String fixJavaScriptFunction(String text) {
        Matcher matcher = FUNCTION_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.replaceFirst("function(");
        } else {
            return text;
        }
    }

    public static boolean isValidVariableName(String name) {
        return VARIABLE_PATTERN.matcher(name).matches();
    }

    public static final boolean isVariableAndSpaceAndPath(String text) {
        return text.matches("^" + VARIABLE_PATTERN_STRING + "\\s+.+");
    }

    public static final boolean isVariable(String text) {
        return VARIABLE_PATTERN.matcher(text).matches();
    }

    public static final boolean isWithinParentheses(String text) {
        return text != null && text.startsWith("(") && text.endsWith(")");
    }

    public static final boolean isCallSyntax(String text) {
        return text.startsWith("call ");
    }

    public static final boolean isCallOnceSyntax(String text) {
        return text.startsWith("callonce ");
    }

    public static final boolean isGetSyntax(String text) {
        return text.startsWith("get ") || text.startsWith("get[");
    }

    public static final boolean isJson(String text) {
        return text.startsWith("{") || text.startsWith("[");
    }

    public static final boolean isXml(String text) {
        return text.startsWith("<");
    }

    public static boolean isXmlPath(String text) {
        return text.startsWith("/");
    }

    public static boolean isXmlPathFunction(String text) {
        return text.matches("^[a-z-]+\\(.+");
    }

    public static final boolean isJsonPath(String text) {
        return text.indexOf('*') != -1 || text.contains("..") || text.contains("[?");
    }

    public static final boolean isDollarPrefixed(String text) {
        return text.startsWith("$");
    }

    public static final boolean isDollarPrefixedJsonPath(String text) {
        return text.startsWith("$.") || text.startsWith("$[") || text.equals("$");
    }

    public static StringUtils.Pair parseCallArgs(String line) {
        int pos = line.indexOf("read(");
        if (pos != -1) {
            pos = line.indexOf(')');
            if (pos == -1) {
                throw new RuntimeException("failed to parse call arguments: " + line);
            }
            return new StringUtils.Pair(line.substring(0, pos + 1), StringUtils.trimToNull(line.substring(pos + 1)));
        }
        pos = line.indexOf(' ');
        if (pos == -1) {
            return new StringUtils.Pair(line, null);
        }
        return new StringUtils.Pair(line.substring(0, pos), StringUtils.trimToNull(line.substring(pos)));
    }

    public Variable call(Variable called, Variable arg, boolean sharedScope) {
        switch (called.type) {
            case JS_FUNCTION:
            case JAVA_FUNCTION:
                return arg == null ? executeFunction(called) : executeFunction(called, new Object[]{arg.getValue()});
            case FEATURE:
                Variable res = callFeature(called.getValue(), arg, -1, sharedScope);
                recurseAndAttach(res.getValue()); // will always be a map, we update entries within
                return res;
            default:
                throw new RuntimeException("not a callable feature or js function: " + called);
        }
    }

    public Variable call(boolean callOnce, String exp, boolean sharedScope) {
        StringUtils.Pair pair = parseCallArgs(exp);
        Variable called = evalKarateExpression(pair.left);
        Variable arg = pair.right == null ? null : evalKarateExpression(pair.right);
        Variable result;
        if (callOnce) {
            result = callOnce(exp, called, arg, sharedScope);
        } else {
            result = call(called, arg, sharedScope);
        }
        if (sharedScope && result.isMap()) {
            setVariables(result.getValue());
        }
        return result;
    }

    private Variable result(ScenarioCall.Result result, boolean sharedScope) {
        if (sharedScope) { // if shared scope
            setConfig(new Config(result.config)); // re-apply config from time of snapshot
            if (result.vars != null) {
                vars.clear(); // clean slate
                vars.putAll(copy(result.vars, false)); // clone for safety     
                init(); // this will also insert magic variables
                return Variable.NULL; // since we already reset the vars above we return null
                // else the call() routine would try to do it again
                // note that shared scope means a return value is meaningless
            }
        }
        return result.value.copy(false); // clone result for safety 
    }

    private Variable callOnce(String cacheKey, Variable called, Variable arg, boolean sharedScope) {
        // IMPORTANT: the call result is always shallow-cloned before returning
        // so that call result (especially if a java Map) is not mutated by other scenarios
        final Map<String, ScenarioCall.Result> CACHE = runtime.featureRuntime.FEATURE_CACHE;
        ScenarioCall.Result result = CACHE.get(cacheKey);
        if (result != null) {
            logger.trace("callonce cache hit for: {}", cacheKey);
            return result(result, sharedScope);
        }
        long startTime = System.currentTimeMillis();
        logger.trace("callonce waiting for lock: {}", cacheKey);
        synchronized (CACHE) {
            result = CACHE.get(cacheKey); // retry
            if (result != null) {
                long endTime = System.currentTimeMillis() - startTime;
                logger.warn("this thread waited {} milliseconds for callonce lock: {}", endTime, cacheKey);
                return result(result, sharedScope);
            }
            // this thread is the 'winner'
            logger.info(">> lock acquired, begin callonce: {}", cacheKey);
            Variable resultValue = call(called, arg, sharedScope);
            // we clone result (and config) here, to snapshot state at the point the callonce was invoked
            // this prevents the state from being clobbered by the subsequent steps of this
            // first scenario that is about to use the result
            Map<String, Variable> clonedVars = called.isFeature() && sharedScope ? copyVariables(false) : null;
            result = new ScenarioCall.Result(resultValue.copy(false), new Config(config), clonedVars);
            CACHE.put(cacheKey, result);
            logger.info("<< lock released, cached callonce: {}", cacheKey);
            return resultValue; // another routine will apply globally if needed
        }
    }

    public Variable callFeature(Feature feature, Variable arg, int index, boolean sharedScope) {
        if (arg == null || arg.isMap()) {
            ScenarioCall call = new ScenarioCall(runtime, feature);
            call.setArg(arg);
            call.setLoopIndex(index);
            call.setSharedScope(sharedScope);
            FeatureRuntime fr = new FeatureRuntime(call);
            fr.run();
            // VERY IMPORTANT ! switch back from called feature js context
            THREAD_LOCAL.set(this);
            runtime.addCallResult(fr.result);
            if (fr.result.isFailed()) {
                KarateException ke = fr.result.getErrorsCombined();
                throw ke;
            } else {
                return fr.getResultVariable();
            }
        } else if (arg.isList() || arg.isJsOrJavaFunction()) {
            List result = new ArrayList();
            List<String> errors = new ArrayList();
            int loopIndex = 0;
            boolean isList = arg.isList();
            Iterator iterator = isList ? arg.<List>getValue().iterator() : null;
            while (true) {
                Variable loopArg;
                if (isList) {
                    loopArg = iterator.hasNext() ? new Variable(iterator.next()) : Variable.NULL;
                } else { // function
                    loopArg = executeFunction(arg, new Object[]{loopIndex});
                }
                if (!loopArg.isMap()) {
                    if (!isList) {
                        logger.info("feature call loop function ended at index {}, returned: {}", loopIndex, loopArg);
                    }
                    break;
                }
                try {
                    Variable loopResult = callFeature(feature, loopArg, loopIndex, sharedScope);
                    result.add(loopResult.getValue());
                } catch (Exception e) {
                    String message = "feature call loop failed at index: " + loopIndex + ", " + e.getMessage();
                    errors.add(message);
                    runtime.logError(message);
                    if (!isList) { // this is a generator function, abort infinite loop !
                        break;
                    }
                }
                loopIndex++;
            }
            if (errors.isEmpty()) {
                return new Variable(result);
            } else {
                String errorMessage = StringUtils.join(errors, '\n');
                throw new KarateException(errorMessage);
            }
        } else {
            throw new RuntimeException("feature call argument is not a json object or array: " + arg);
        }
    }

    public Variable evalJsonPath(Variable v, String path) {
        Json json = new Json(v.getValueAndForceParsingAsJson());
        try {
            return new Variable(json.get(path));
        } catch (PathNotFoundException e) {
            return Variable.NOT_PRESENT;
        }
    }

    public static Variable evalXmlPath(Variable xml, String path) {
        NodeList nodeList;
        Node doc = xml.getAsXml();
        try {
            nodeList = XmlUtils.getNodeListByPath(doc, path);
        } catch (Exception e) {
            // hack, this happens for xpath functions that don't return nodes (e.g. count)
            String strValue = XmlUtils.getTextValueByPath(doc, path);
            Variable v = new Variable(strValue);
            if (path.startsWith("count")) { // special case
                return new Variable(v.getAsInt());
            } else {
                return v;
            }
        }
        int count = nodeList.getLength();
        if (count == 0) { // xpath / node does not exist !
            return Variable.NOT_PRESENT;
        }
        if (count == 1) {
            return nodeToValue(nodeList.item(0));
        }
        List list = new ArrayList();
        for (int i = 0; i < count; i++) {
            Variable v = nodeToValue(nodeList.item(i));
            list.add(v.getValue());
        }
        return new Variable(list);
    }

    private static Variable nodeToValue(Node node) {
        int childElementCount = XmlUtils.getChildElementCount(node);
        if (childElementCount == 0) {
            // hack assuming this is the most common "intent"
            return new Variable(node.getTextContent());
        }
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            return new Variable(node);
        } else { // make sure we create a fresh doc else future xpath would run against original root
            return new Variable(XmlUtils.toNewDocument(node));
        }
    }

    public Variable evalJsonPathOnVariableByName(String name, String path) {
        return evalJsonPath(vars.get(name), path);
    }

    public Variable evalXmlPathOnVariableByName(String name, String path) {
        return evalXmlPath(vars.get(name), path);
    }

    public Variable evalKarateExpression(String text) {
        text = StringUtils.trimToNull(text);
        if (text == null) {
            return Variable.NULL;
        }
        // don't re-evaluate if this is clearly a direct reference to a variable
        // this avoids un-necessary conversion of xml into a map in some cases
        // e.g. 'Given request foo' - where foo is a Variable of type XML      
        if (vars.containsKey(text)) {
            return vars.get(text);
        }
        boolean callOnce = isCallOnceSyntax(text);
        if (callOnce || isCallSyntax(text)) { // special case in form "callBegin foo arg"
            if (callOnce) {
                text = text.substring(9);
            } else {
                text = text.substring(5);
            }
            return call(callOnce, text, false);
        } else if (isDollarPrefixedJsonPath(text)) {
            return evalJsonPathOnVariableByName(RESPONSE, text);
        } else if (isGetSyntax(text) || isDollarPrefixed(text)) { // special case in form
            // get json[*].path
            // $json[*].path
            // get /xml/path
            // get xpath-function(expression)
            int index = -1;
            if (text.startsWith("$")) {
                text = text.substring(1);
            } else if (text.startsWith("get[")) {
                int pos = text.indexOf(']');
                index = Integer.valueOf(text.substring(4, pos));
                text = text.substring(pos + 2);
            } else {
                text = text.substring(4);
            }
            String left;
            String right;
            if (isDollarPrefixedJsonPath(text)) { // edge case get[0] $..foo
                left = RESPONSE;
                right = text;
            } else if (isVariableAndSpaceAndPath(text)) {
                int pos = text.indexOf(' ');
                right = text.substring(pos + 1);
                left = text.substring(0, pos);
            } else {
                StringUtils.Pair pair = parseVariableAndPath(text);
                left = pair.left;
                right = pair.right;
            }
            Variable sv;
            if (isXmlPath(right) || isXmlPathFunction(right)) {
                sv = evalXmlPathOnVariableByName(left, right);
            } else {
                sv = evalJsonPathOnVariableByName(left, right);
            }
            if (index != -1 && sv.isList()) {
                List list = sv.getValue();
                if (!list.isEmpty()) {
                    return new Variable(list.get(index));
                }
            }
            return sv;
        } else if (isJson(text)) {
            Json json = new Json(text);
            return evalEmbeddedExpressions(new Variable(json.asMapOrList()));
        } else if (isXml(text)) {
            Document doc = XmlUtils.toXmlDoc(text);
            return evalEmbeddedExpressions(new Variable(doc));
        } else if (isXmlPath(text)) {
            return evalXmlPathOnVariableByName(RESPONSE, text);
        } else {
            // old school function declarations e.g. function() { } need wrapping in graal
            if (isJavaScriptFunction(text)) {
                text = "(" + text + ")";
            }
            // js expressions e.g. foo, foo(bar), foo.bar, foo + bar, foo + '', 5, true
            // including arrow functions e.g. x => x + 1
            return evalJs(text);
        }
    }

}
