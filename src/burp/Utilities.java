package burp;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class Utilities {

    public static final String version = "1.03";
    public static String name = "uninitialised";
    private static PrintWriter stdout;
    private static PrintWriter stderr;
    static final boolean DEBUG = false;
    static boolean chopNestedResponses = false;
    static final byte CONFIRMATIONS = 5;
    static boolean supportsHTTP2 = true;

    static AtomicBoolean unloaded = new AtomicBoolean(false);


    static final byte PARAM_HEADER = 7;

    static IBurpExtenderCallbacks callbacks;
    static IExtensionHelpers helpers;
    static HashSet<String> phpFunctions = new HashSet<>();
    static ArrayList<String> paramNames = new ArrayList<>();
    static HashSet<String> boringHeaders = new HashSet<>();
    static Set<String> reportedParams = ConcurrentHashMap.newKeySet();

    static CircularFifoQueue<Long> requestTimes = new CircularFifoQueue<>(100);

    static AtomicInteger requestCount = new AtomicInteger(0);

    private static final String CHARSET = "0123456789abcdefghijklmnopqrstuvwxyz"; // ABCDEFGHIJKLMNOPQRSTUVWXYZ
    private static final String START_CHARSET = "ghijklmnopqrstuvwxyz";
    static Random rnd = new Random();

    static ConfigurableSettings globalSettings;


//    static byte[] addCacheBuster(byte[] req, String cacheBuster, boolean pathBust) {
//
//        if (pathBust) {
//            String path = Utilities.getPathFromRequest(req);
//            if (path.length() > 1 && path.charAt(1) != '?') {
//                char c = path.charAt(1);
//                String encoded = String.format("%02x", (int) c);
//                req = Utilities.setPath(req, "/%" + encoded + path.substring(2));
//            }
//            else {
//                // this method sucks - only used as a last resort
//                req = Utilities.replaceFirst(req, "/", "//");
//            }
//        }
//
//        req = Utilities.appendToQuery(req, cacheBuster+"=1");
//        req = Utilities.addOrReplaceHeader(req, "Origin", "https://"+cacheBuster+".com");
//        req = Utilities.appendToHeader(req, "Accept", ", text/"+cacheBuster);
//        req = Utilities.appendToHeader(req, "Accept-Encoding", ", "+cacheBuster);
//        req = Utilities.appendToHeader(req, "User-Agent", " " + cacheBuster);
//        return req;
//    }


    static JFrame getBurpFrame()
    {
        for(Frame f : Frame.getFrames())
        {
            if(f.isVisible() && f.getTitle().startsWith(("Burp Suite")))
            {
                return (JFrame) f;
            }
        }
        return null;
    }

    Utilities(final IBurpExtenderCallbacks incallbacks, HashMap<String, Object> settings, String name) {
        Utilities.name = name;
        callbacks = incallbacks;
        stdout = new PrintWriter(callbacks.getStdout(), true);
        stderr = new PrintWriter(callbacks.getStderr(), true);
        helpers = callbacks.getHelpers();

        Utilities.out("Using albinowaxUtils v"+version);
        Utilities.out("This extension should be run on the latest version of Burp Suite. Using an older version of Burp may cause impaired functionality.");

        if (settings != null) {
            globalSettings = new ConfigurableSettings(settings);
            if (DEBUG) {
                globalSettings.printSettings();
            }
        }
    }

    static boolean isBurpPro() {
        return callbacks.getBurpVersion()[0].contains("Professional");
    }

    static String getResource(String name) {
        return new Scanner(Utilities.class.getResourceAsStream(name), "UTF-8").useDelimiter("\\A").next();
    }

    // fixme currently only handles numbers
    static String getSetting(String name) {
        int depth = StringUtils.countMatches(name, ".") + 1;
        String json = callbacks.saveConfigAsJson(name);
        String value = json.split("\n")[depth].split(":", 2)[1];
        return value;
    }

    static void showError(Exception e) {
        Utilities.out("Error in thread: "+e.getMessage() +". See error pane for stack trace.");
        e.printStackTrace(stderr);
    }

    static String getNameFromType(byte type) {
        switch (type) {
            case IParameter.PARAM_BODY:
                return "body";
            case IParameter.PARAM_URL:
                return "url";
            case IParameter.PARAM_COOKIE:
                return "cookie";
            case IParameter.PARAM_JSON:
                return "json";
            case Utilities.PARAM_HEADER:
                return "header";
            default:
                return "unknown";
        }
    }


    static int generate(int seed, int count, List<String> accumulator)
    {

        int num = seed;
        int limit = seed + count;
        for (; num < limit; num++) {
            String word = num2word(num);
            if(word != null)
            {
                accumulator.add(word);
            }
            else
            {
                limit++;
            }
        }
        return num;
    }

    private static String num2word(int num)
    {
        String number = num2String(num);
        if (number.contains("0"))
        {
            return null;
        }
        return number;
    }

    private static char[] DIGITS = {'0', 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'};

    private static String num2String(int i) {

        if(i < 0)
        {
            throw new IllegalArgumentException("+ve integers only please");
        }

        char buf[] = new char[7];
        int charPos = 6;

        i = -i;

        while (i <= -DIGITS.length) {
            buf[charPos--] = DIGITS[-(i % DIGITS.length)];
            i = i / DIGITS.length;
        }
        buf[charPos] = DIGITS[-i];

        return new String(buf, charPos, (7 - charPos));
    }


    static String filter(String input, String safeChars) {
        StringBuilder out = new StringBuilder(input.length());
        HashSet<Character> charset = new HashSet<>();
        charset.addAll(safeChars.chars().mapToObj(c -> (char) c).collect(Collectors.toList()));
        for(char c: input.toCharArray()) {
            if (charset.contains(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    static boolean mightBeOrderBy(String name, String value) {
        return (name.toLowerCase().contains("order") ||
                name.toLowerCase().contains("sort")) ||
                value.toLowerCase().equals("asc") ||
                value.toLowerCase().equals("desc") ||
                (StringUtils.isNumeric(value) && Double.parseDouble(value) <= 1000) ||
                (value.length() < 20 && StringUtils.isAlpha(value));
    }

    static boolean mightBeIdentifier(String value) {
        for (int i=0; i<value.length(); i++) {
            char x = value.charAt(i);
            if (!(CharUtils.isAsciiAlphanumeric(x) || x == '.' || x == '-' || x == '_' || x == ':' || x == '$') ) {
                return false;
            }
        }
        return true;
    }

    static Attack buildTransformationAttack(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint, String leftAnchor, String payload, String rightAnchor) {

        IHttpRequestResponse req = attemptRequest(baseRequestResponse.getHttpService(),
                insertionPoint.buildRequest(helpers.stringToBytes(insertionPoint.getBaseValue() + leftAnchor + payload + rightAnchor)));

        return new Attack(Utilities.highlightRequestResponse(req, leftAnchor, leftAnchor+payload+rightAnchor, insertionPoint), null, payload, "");
    }

    static boolean isInPath(IScannerInsertionPoint insertionPoint) {
        byte type = insertionPoint.getInsertionPointType();
        boolean isInPath = (type == IScannerInsertionPoint.INS_URL_PATH_FILENAME ||
                type == IScannerInsertionPoint.INS_URL_PATH_FOLDER);

        if (!isInPath && type == IScannerInsertionPoint.INS_USER_PROVIDED) {
            final String injectionCanary = "zxcvcxz";
            String path = Utilities.getPathFromRequest(insertionPoint.buildRequest(injectionCanary.getBytes()));
            if (path.contains(injectionCanary)) {
                if (path.contains("?")) {
                    if (path.indexOf(injectionCanary) < path.indexOf("?")) {
                        isInPath = true;
                    }
                }
                else {
                    isInPath = true;
                }
            }
        }

        return isInPath;
    }

    static boolean invertable(String value) {
        return !value.equals(invert(value));
    }

    static Object invert(String value) {
        if (value != null) {
            if (value.equals("true")) {
                return false;
            } else if (value.equals("false")) {
                return true;
            }
            else if (value.equals("1")) {
                return 0;
            }
            else if (value.equals("0")) {
                return 1;
            }
        }
        return value;
    }

    static String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        sb.append(START_CHARSET.charAt(rnd.nextInt(START_CHARSET.length())));
        for (int i = 1; i < len; i++)
            sb.append(CHARSET.charAt(rnd.nextInt(CHARSET.length())));
        return sb.toString();
    }

    static String mangle(String seed) {
        Random seededRandom = new Random(seed.hashCode());
        StringBuilder sb = new StringBuilder(7);
        sb.append(START_CHARSET.charAt(seededRandom.nextInt(START_CHARSET.length())));
        for (int i = 1; i < 8; i++)
            sb.append(CHARSET.charAt(seededRandom.nextInt(CHARSET.length())));
        return sb.toString();
    }

    static void out(String message) {
        stdout.println(message);
    }
    static void err(String message) {
        stderr.println(message);
    }

    static void log(String message) {
        if (DEBUG) {
            stdout.println(message);
        }
    }

    static String getHeaders(byte[] response) {
        if (response == null) { return ""; }
        int bodyStart = Utilities.getBodyStart(response);
        String body = Utilities.helpers.bytesToString(Arrays.copyOfRange(response, 0, bodyStart));
        body = body.substring(body.indexOf("\n")+1);
        return body;
    }

    static boolean similarIsh(Attack noBreakGroup, Attack breakGroup, Attack noBreak, Attack doBreak) {

        for (String key: noBreakGroup.getPrint().keySet()) {
            Object noBreakVal = noBreakGroup.getPrint().get(key);

            if(key.equals("input_reflections") && noBreakVal.equals(Attack.INCALCULABLE)) {
                continue;
            }

            // if this attribute is inconsistent, make sure it's different this time
            if (!breakGroup.getPrint().containsKey(key)) {
                if (!noBreakVal.equals(doBreak.getPrint().get(key))) {
                    return false;
                }
            }
            else if (!noBreakVal.equals(breakGroup.getPrint().get(key))) {
                // if it's consistent and different, these responses definitely don't match
                return false;
            }
        }

        for (String key: breakGroup.getPrint().keySet()) {
            if (!noBreakGroup.getPrint().containsKey(key)) {
                // if this attribute is inconsistent, make sure it's different this time
                if (!breakGroup.getPrint().get(key).equals(noBreak.getPrint().get(key))){
                    return false;
                }
            }
        }

        return true;
    }

    static boolean similar(Attack doNotBreakAttackGroup, Attack individualBreakAttack) {
        //if (!candidate.getPrint().keySet().equals(individualBreakAttack.getPrint().keySet())) {
        //    return false;
        //}

        for (String key: doNotBreakAttackGroup.getPrint().keySet()) {
            if (!individualBreakAttack.getPrint().containsKey(key)){
                return false;
            }
            if (individualBreakAttack.getPrint().containsKey(key) && !individualBreakAttack.getPrint().get(key).equals(doNotBreakAttackGroup.getPrint().get(key))) {
                return false;
            }
        }

        return true;
    }

    static boolean verySimilar(Attack attack1, Attack attack2) {
        if (!attack1.getPrint().keySet().equals(attack2.getPrint().keySet())) {
            return false;
        }

        for (String key: attack1.getPrint().keySet()) {
            if(key.equals("input_reflections") && (attack1.getPrint().get(key).equals(Attack.INCALCULABLE) || attack2.getPrint().get(key).equals(Attack.INCALCULABLE))) {
                continue;
            }

            if (attack2.getPrint().containsKey(key) && !attack2.getPrint().get(key).equals(attack1.getPrint().get(key))) {
                return false;
            }
        }

        return true;
    }

    static byte[] filterResponse(byte[] response) {

        if (response == null) {
            return new byte[]{'n','u','l','l'};
        }
        byte[] filteredResponse;
        IResponseInfo details = helpers.analyzeResponse(response);

        String inferredMimeType = details.getInferredMimeType();
        if(inferredMimeType.isEmpty()) {
            inferredMimeType = details.getStatedMimeType();
        }
        inferredMimeType = inferredMimeType.toLowerCase();

        if(inferredMimeType.contains("text") || inferredMimeType.equals("html") || inferredMimeType.contains("xml") || inferredMimeType.contains("script") || inferredMimeType.contains("css") || inferredMimeType.contains("json")) {
            filteredResponse = helpers.stringToBytes(helpers.bytesToString(response).toLowerCase());
        }
        else {
            String headers = helpers.bytesToString(Arrays.copyOfRange(response, 0, details.getBodyOffset())) + details.getInferredMimeType();
            filteredResponse = helpers.stringToBytes(headers.toLowerCase());
        }

        if(details.getStatedMimeType().toLowerCase().contains("json") && (inferredMimeType.contains("json") || inferredMimeType.contains("javascript"))) {
            String headers = helpers.bytesToString(Arrays.copyOfRange(response, 0, details.getBodyOffset()));
            String body =  helpers.bytesToString(Arrays.copyOfRange(response, details.getBodyOffset(), response.length));
            filteredResponse = helpers.stringToBytes(headers + StringEscapeUtils.unescapeJson(body));
        }

        return filteredResponse;
    }

    static boolean identical(Attack candidate, Attack attack2) {
        if (candidate == null) {
            return false;
        }
        return candidate.getPrint().equals(attack2.getPrint());
    }

    static String getBody(byte[] response) {
        if (response == null) { return ""; }
        int bodyStart = Utilities.getBodyStart(response);
        String body = Utilities.helpers.bytesToString(Arrays.copyOfRange(response, bodyStart, response.length));
        return body;
    }

    static byte[] getBodyBytes(byte[] response) {
        if (response == null) { return null; }
        int bodyStart = Utilities.getBodyStart(response);
        return Arrays.copyOfRange(response, bodyStart, response.length);
    }

    static String generateCanary() {
        return randomString(4+rnd.nextInt(7)) + Integer.toString(rnd.nextInt(9));
    }

    private static String sensibleURL(URL url) {
        String out = url.toString();
        if (url.getDefaultPort() == url.getPort()) {
            out = out.replaceFirst(":" + Integer.toString(url.getPort()), "");
        }
        return out;
    }

    static URL getURL(byte[] request, IHttpService service) {
        URL url;
        try {
            url = new URL(service.getProtocol(), service.getHost(), service.getPort(), getPathFromRequest(request));
        } catch (java.net.MalformedURLException e) {
            url = null;
        }
        return url;
    }

    static URL getURL(IHttpRequestResponse request) {
        return getURL(request.getRequest(), request.getHttpService());
    }

    static int parseArrayIndex(String key) {
        try {
            if (key.length() > 2 && key.startsWith("[") && key.endsWith("]")) {
                return Integer.parseInt(key.substring(1, key.length() - 1));
            }
        }
        catch (NumberFormatException e) {

        }
        return -1;
    }

    static boolean mightBeFunction(String value) {
        return phpFunctions.contains(value);
    }


    static byte[] setPath(byte[] request, String newPath) {
        String oldPath = getPathFromRequest(request);
        return replaceFirst(request, oldPath.getBytes(), newPath.getBytes());
    }


    //    static IHttpRequestResponseWithMarkers highlight(IHttpRequestResponse req, String toHighlight, boolean ignoreHeaders) {
//        ArrayList<int[]> reqHighlights = new ArrayList<>();
//        ArrayList<int[]> respHighlights = new ArrayList<>();
//        getMatches(req.getResponse(), req.getRequest().length);
//        callbacks.applyMarkers()
//    }
    // records from the first space to the second space
    // also gets code from response!
    static String getPathFromRequest(byte[] request) {
        int i = 0;
        boolean recording = false;
        StringBuilder path = new StringBuilder("");
        while (i < request.length) {
            byte x = request[i];

            if (recording) {
                if (x != ' ') {
                    path.append((char) x);
                } else {
                    break;
                }
            } else {
                if (x == ' ') {
                    recording = true;
                }
            }
            i++;
        }
        return path.toString();
    }

    static boolean isHTTP2(byte[] request) {
        int i = 0;

        while (i < request.length) {
            if (request[i] == '\r') {
                break;
            }
            i++;
        }

        if (i < 6) {
            return false;
        }

        return "HTTP/2".equals(new String(Arrays.copyOfRange(request, i-6,i)));
    }

    static String getExtension(byte[] request) {
        String url = getPathFromRequest(request);
        int query_start = url.indexOf('?');
        if (query_start == -1) {
            query_start = url.length();
        }
        url = url.substring(0, query_start);
        int last_dot = url.lastIndexOf('.');
        if (last_dot == -1) {
            return "";
        }
        else {
            return url.substring(last_dot);
        }
    }


    static byte[] replace(byte[] request, String find, String replace) {
        return replace(request, find.getBytes(), replace.getBytes());
    }

    static IHttpRequestResponse fetchFromSitemap(URL url) {
        IHttpRequestResponse[] pages = callbacks.getSiteMap(sensibleURL(url));
        for (IHttpRequestResponse page : pages) {
            if (page.getResponse() != null) {
                if (url.equals(getURL(page))) {
                    return page;
                }
            }
        }
        return null;
    }

    static int countByte(byte[] response, byte match) {
        int count = 0;
        int i = 0;
        while (i < response.length) {
            if (response[i] == match) {
                count +=1 ;
            }
            i += 1;
        }
        return count;
    }

    static int countMatches(Resp response, String match){
        byte[] resp = response.getReq().getResponse();
        if (resp == null || resp.length == 0) {
            return 0;
        }
        return countMatches(resp, match.getBytes());
    }

    static int countMatches(byte[] response, byte[] match) {
        int matches = 0;
        if (match.length < 4) {
            return matches;
        }

        int start = 0;
        // Utilities.out("#"+response.length);
        while (start < response.length) {
            start = helpers.indexOf(response, match, true, start, response.length);
            if (start == -1)
                break;
            matches += 1;
            start += match.length;
        }

        return matches;
    }

    static byte[] replace(byte[] request, byte[] find, byte[] replace) {
        return replace(request, find, replace, -1);
    }

    static byte[] replaceFirst(byte[] request, String find, String replace) {
        return replace(request, find.getBytes(), replace.getBytes(), 1);
    }

    static byte[] replaceFirst(byte[] request, byte[] find, byte[] replace) {
        return replace(request, find, replace, 1);
    }


    private static byte[] replace(byte[] request, byte[] find, byte[] replace, int limit) {
        List<int[]> matches = getMatches(request, find, -1);
        if (limit != -1 && limit < matches.size()) {
            matches = matches.subList(0, limit);
        }

        if (matches.size() == 0) {
            return request;
        }

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (int i=0;i<matches.size();i++) {
                if (i == 0) {
                    outputStream.write(Arrays.copyOfRange(request, 0, matches.get(i)[0]));
                }
                else {
                    outputStream.write(Arrays.copyOfRange(request, matches.get(i-1)[1], matches.get(i)[0]));
                }
                outputStream.write(replace);

                if (i==matches.size()-1) {
                    outputStream.write(Arrays.copyOfRange(request, matches.get(i)[1], request.length));
                    break;
                }
            }
            request = outputStream.toByteArray();
        } catch (IOException e) {
            Utilities.out("IO Exception in replace() somehow");
            return null;
        }

        return request;
    }



    static byte[] appendToQueryzzz(byte[] request, String suffix) {
        if (suffix == null || suffix.equals("")) {
            return request;
        }

        int lineEnd = 0;
        while (lineEnd < request.length && request[lineEnd++] != '\n') {
        }

        int queryStart = 0;
        while (queryStart < lineEnd && request[queryStart++] != '?') {
        }

        if (queryStart >= lineEnd) {
            suffix = "?" + suffix;
        }
        else {
            suffix = "&";
        }

        return replace(request, " HTTP/".getBytes(), (suffix+" HTTP/").getBytes());
    }


    // does not update content length
    static byte[] setBody(byte[] req, String body) {
        try {
            ByteArrayOutputStream synced = new ByteArrayOutputStream();
            synced.write(Arrays.copyOfRange(req, 0, Utilities.getBodyStart(req)));
            synced.write(body.getBytes());
            return  synced.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] appendToQuery(byte[] request, String suffix) {
        String url = getPathFromRequest(request);
        if(url.contains("?")) {
            if (url.indexOf("?") == url.length()-1) {
                // add suffix
            }
            else {
                suffix = "&" + suffix;
            }
        }
        else {
            suffix = "?" + suffix;
        }

        return replaceFirst(request, url.getBytes(), (url+suffix).getBytes());
    }

    static byte[] appendToPath(byte[] request, String suffix) {
        if (suffix == null || suffix.equals("")) {
            return request;
        }

        int i = 0;
        while (i < request.length && request[i++] != '\n') {
        }

        int j = 0;
        while (j < i && request[j++] != '?') {
        }

        if(j >= i) {
            request = replace(request, " HTTP/".getBytes(), (suffix+" HTTP/").getBytes());
        }
        else {
            request = replace(request, "?".getBytes(), (suffix+"?").getBytes()); // fixme replace can't handle single-char inputs
        }

        return request;
    }

    static List<int[]> getMatches(byte[] response, byte[] match, int giveUpAfter) {
        if (giveUpAfter == -1) {
            giveUpAfter = response.length;
        }

        if (match.length == 0) {
            throw new RuntimeException("Utilities.getMatches() on the empty string is not allowed)");
        }

        List<int[]> matches = new ArrayList<>();

//        if (match.length < 4) {
//            return matches;
//        }

        int start = 0;
        while (start < giveUpAfter) {
            start = helpers.indexOf(response, match, true, start, giveUpAfter);
            if (start == -1)
                break;
            matches.add(new int[]{start, start + match.length});
            start += match.length;
        }

        return matches;
    }

    public static byte[] setMethod(byte[] request, String newMethod) {
        int i = 0;
        while (request[++i] != ' ') { }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(newMethod.getBytes());
            outputStream.write(Arrays.copyOfRange(request, i, request.length));
        } catch (IOException e) {

        }
        return outputStream.toByteArray();
    }

    public static void doActiveScan(IHttpRequestResponse req, int[] offsets) {
        String host = helpers.analyzeRequest(req).getUrl().getHost();
        int port = helpers.analyzeRequest(req).getUrl().getPort();
        boolean useHTTPS = helpers.analyzeRequest(req).getUrl().toString().startsWith("https");
        ArrayList<int[]> offsetList = new ArrayList<>();
        offsetList.add(offsets);
        try {
            callbacks.doActiveScan(
                    host, port, useHTTPS, req.getRequest(), offsetList
            );
        } catch (IllegalArgumentException e) {
            Utilities.err("Couldn't scan, bad insertion points: "+Arrays.toString(offsetList.get(0)));
        }
    }

    static String fuzzSuffix() {
        if(Utilities.globalSettings.getBoolean("fuzz detect")) {
            return "<a`'\"${{\\"; // <a
        }
        else {
            return "";
        }
    }

    static String toCanary(String payload) {
        return globalSettings.getString("canary") + mangle(payload);
    }

    public static int getBodyStart(byte[] response) {
        int i = 0;
        int newlines_seen = 0;
        while (i < response.length) {
            byte x = response[i];
            if (x == '\n') {
                newlines_seen++;
            } else if (x != '\r') {
                newlines_seen = 0;
            }

            if (newlines_seen == 2) {
                i += 1;
                break;
            }
            i += 1;
        }

        return i;
    }

    static String getStartType(byte[] response) {
        int i = getBodyStart(response);

        String start = "";
        if (i == response.length) {
            start = "[blank]";
        }
        else if (response[i] == '<') {
            while (i < response.length && (response[i] != ' ' && response[i] != '\n' && response[i] != '\r' && response[i] != '>')) {
                start += (char) (response[i] & 0xFF);
                i += 1;
            }
        }
        else {
            start = "text";
        }

        return start;
    }

    public static byte[] appendToHeader(byte[] request, String header, String value) {
        String baseValue = getHeader(request, header);
        if ("".equals(baseValue)) {
            return request;
        }
        return addOrReplaceHeader(request, header, baseValue+value);
    }

    public static String getHeader(byte[] request, String header) {
        int[] offsets = getHeaderOffsets(request, header);
        if (offsets == null) {
            return "";
        }
        String value = helpers.bytesToString(Arrays.copyOfRange(request, offsets[1], offsets[2]));
        return value;
    }

    public static String getMethod(byte[] request) {
        int i = 0;
        while (request[i] != ' ') {
            i++;
        }
        return new String(Arrays.copyOfRange(request, 0, i));
    }

    public static ArrayList<PartialParam> getQueryParams(byte[] request) {
        ArrayList<PartialParam> params = new ArrayList<>();

        if (request.length == 0) {
            return params;
        }

        int i = 0;
        while(request[i] != '?') {
            i += 1;
            if (i == request.length) {
                return params;
            }
        }

        i += 1;

        while (request[i] != ' ') {
            StringBuilder name = new StringBuilder();
            while (request[i] != ' ') {
                char c = (char) request[i];
                if (c == '=') {
                    i++;
                    break;
                }
                name.append(c);
                i++;
            }

//            if (request[i] == ' ') {
//                break;
//            }

            int valueStart = i;
            int valueEnd;

            while (true) {
                char c = (char) request[i];
                if (c == '&') {
                    valueEnd = i;
                    i++;
                    break;
                }
                if (c == ' ') {
                    valueEnd = i;
                    break;
                }

                i++;
            }

            //Utilities.out("Param: "+name.toString()+"="+value.toString() + " | " + (char) request[valueStart] + " to " + (char) request[valueEnd]);
            params.add(new PartialParam(name.toString(), valueStart, valueEnd, IParameter.PARAM_URL));
            //Utilities.out(Utilities.helpers.bytesToString(new RawInsertionPoint(request, valueStart, valueEnd).buildRequest("injected".getBytes())));
        }


        return params;
    }

    public static boolean containsBytes(byte[] request, byte[] value) {
        if (request == null) {
            return false;
        }
        return helpers.indexOf(request, value, false, 0, request.length) != -1;
    }

    static boolean contains(Resp response, String match){
        byte[] resp = response.getReq().getResponse();
        if (resp == null || resp.length == 0) {
            return false;
        }
        return helpers.indexOf(resp, match.getBytes(), false, 0, resp.length) != -1;
    }

    public static byte[] setHeader(byte[] request, String header, String value) {
        return setHeader(request, header, value, false);
    }

    public static byte[] setHeader(byte[] request, String header, String value, boolean tolerateMissing) {
        int[] offsets = getHeaderOffsets(request, header);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write( Arrays.copyOfRange(request, 0, offsets[1]));
            outputStream.write(helpers.stringToBytes(value));
            outputStream.write(Arrays.copyOfRange(request, offsets[2], request.length));
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Req creation unexpectedly failed");
        } catch (NullPointerException e) {
            if (tolerateMissing) {
                return request;
            }

            Utilities.out("header locating fail: " + header);
            Utilities.out("'" + helpers.bytesToString(request) + "'");
            throw new RuntimeException("Can't find the header: "+header);
        }
    }

    public static String encodeJSON(String input) {
        input = input.replace("\\", "\\\\");
        input = input.replace("\"", "\\\"");
        return input;
    }

    public static int[] getHeaderOffsets(byte[] request, String header) {
        int i = 0;
        int end = request.length;
        while (i < end) {
            int line_start = i;
            i+=1; // allow headers starting with whitespace
            while (i < end && request[i++] != ' ') {
            }
            byte[] header_name = Arrays.copyOfRange(request, line_start, i - 2);
            int headerValueStart = i;
            while (i < end && request[i++] != '\n') {
            }
            if (i == end) {
                break;
            }

            String header_str = helpers.bytesToString(header_name);

            if (header.equals(header_str)) {
                int[] offsets = {line_start, headerValueStart, i - 2};
                return offsets;
            }

            if (i + 2 < end && request[i] == '\r' && request[i + 1] == '\n') {
                break;
            }
        }
        return null;
    }

    public static PartialParam paramify(byte[] request, String name, String target, String fakeBaseValue) {
//        // todo pass in value maybe
//        if (target.length() != basevalue.length()) {
//            throw new RuntimeException("target length must equal basevalue length");
//        }
        int start = Utilities.helpers.indexOf(request, target.getBytes(), true, 0, request.length);
        if (start == -1) {
            throw new RuntimeException("Failed to find target");
        }
        int end = start + target.length();
        return new PartialParam(name, start, end);
    }

    public static byte[] addOrReplaceHeader(byte[] request, String header, String value) {
        if (getHeaderOffsets(request, header) != null) {
            return setHeader(request, header, value);
        }
        return replaceFirst(request, "\r\n\r\n".getBytes(), ("\r\n"+header+": "+value+"\r\n\r\n").getBytes());
    }

    // todo refactor to use getHeaderOffsets
    // fixme fails if the modified header is the last header
    public static byte[] addOrReplaceHeaderOld(byte[] request, String header, String value) {
        try {
            int i = 0;
            int end = request.length;
            while (i < end && request[i++] != '\n') {
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            while (i < end) {
                int line_start = i;
                while (i < end && request[i++] != ' ') {
                }
                byte[] header_name = Arrays.copyOfRange(request, line_start, i - 2);
                int headerValueStart = i;
                while (i < end && request[i++] != '\n') {
                }
                if (i == end) {
                    break;
                }

                if(i+2<end && request[i] == '\r' && request[i+1] == '\n') {
                    outputStream.write(Arrays.copyOfRange(request, 0, i));
                    outputStream.write(helpers.stringToBytes(header + ": " + value+"\r\n"));
                    outputStream.write(Arrays.copyOfRange(request, i, end));
                    return outputStream.toByteArray();
                }

                String header_str = helpers.bytesToString(header_name);

                if (header.equals(header_str)) {

                    outputStream.write(Arrays.copyOfRange(request, 0, headerValueStart));
                    outputStream.write(helpers.stringToBytes(value));
                    outputStream.write(Arrays.copyOfRange(request, i-2, end));
                    return outputStream.toByteArray();
                }
            }
            outputStream.write(Arrays.copyOfRange(request, 0, end-2));
            outputStream.write(helpers.stringToBytes(header + ": " + value+"\r\n\r\n"));
            return outputStream.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Req creation unexpectedly failed");
        }
    }

    static boolean isResponse(byte[] data) {
        byte[] start = Arrays.copyOfRange(data, 0, 5);
        return (helpers.bytesToString(start).equals("HTTP/"));
    }

    public static byte[] fixContentLength(byte[] request) {
        if (countMatches(request, helpers.stringToBytes("Content-Length: ")) > 0) {
            int start = Utilities.getBodyStart(request);
            int contentLength = request.length - start;
            return setHeader(request, "Content-Length", Integer.toString(contentLength), true);
        }
        else {
            return request;
        }
    }

//    static byte[] addBulkParams(byte[] request, String name, String value, byte type) {
//
//    }

    static List<IParameter> getHeaderInsertionPoints(byte[] request, String[] to_poison) {
        List<IParameter> params = new ArrayList<>();
        int end = getBodyStart(request);
        int i = 0;
        while(request[i++] != '\n' && i < end) {}
        while(i<end) {
            int line_start = i;
            while(i < end && request[i++] != ' ') {}
            byte[] header_name = Arrays.copyOfRange(request, line_start, i-2);
            int headerValueStart = i;
            while(i < end && request[i++] != '\n') {}
            if (i == end) { break; }

            String header_str = helpers.bytesToString(header_name);
            for (String header: to_poison) {
                if (header.equals(header_str)) {
                    params.add(new PartialParam(header, headerValueStart, i-2));
                }
            }
        }
        return params;
    }


    static List<IParameter> getExtraInsertionPoints(byte[] request) { //
        List<IParameter> params = new ArrayList<>();
        int end = getBodyStart(request);
        int i = 0;
        while(i < end && request[i++] != ' ') {} // walk to the url start
        while(i < end) {
            byte c = request[i];
            if (c == ' ' ||
                    c == '?' ||
                    c == '#') {
                break;
            }
            i++;
        }

        params.add(new PartialParam("path", i, i));
        while(request[i++] != '\n' && i < end) {}

        String[] to_poison = {"User-Agent", "Referer", "X-Forwarded-For", "Host"};
        params.addAll(getHeaderInsertionPoints(request, to_poison));

        return params;
    }

    static boolean isHTTP(URL url) {
        String protocol = url.getProtocol().toLowerCase();
        return "https".equals(protocol);
    }

    static IHttpRequestResponse highlightRequestResponse(IHttpRequestResponse attack, String responseHighlight, String requestHighlight, IScannerInsertionPoint insertionPoint) {
        List<int[]> requestMarkers = new ArrayList<>(1);
        if (requestHighlight != null && requestHighlight.length() > 2) {
            requestMarkers.add(insertionPoint.getPayloadOffsets(requestHighlight.getBytes()));
        }

        List<int[]> responseMarkers = new ArrayList<>(1);
        if (responseHighlight != null) {
            responseMarkers = getMatches(attack.getResponse(), responseHighlight.getBytes(), -1);
        }

        attack = callbacks.applyMarkers(attack, requestMarkers, responseMarkers);
        return attack;
    }

    static ThreadLocal<Integer> goAcceleratorPort = new ThreadLocal<>();
    static AtomicInteger nextPort = new AtomicInteger(1901);

    static IHttpRequestResponse fetchWithGo(IHttpService service, byte[] req) {
        int port = goAcceleratorPort.get();
        if (port == 0) {
            goAcceleratorPort.set(nextPort.getAndIncrement());
        }
        try {
            Utilities.out("Routing request to "+port);
            Socket sock = new Socket("127.0.0.1", port);
            String preppedService = service.getProtocol()+"://"+service.getHost()+":"+service.getPort();
            sock.getOutputStream().write((preppedService+"\u0000|\u0000"+helpers.bytesToString(req)+"\u0000|\u0000").getBytes());
            byte[] readBuffer = new byte[4096];
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int read;
            while (true) {
                read = sock.getInputStream().read(readBuffer);
                if (read == -1) {
                    break;
                }
                response.write(Arrays.copyOfRange(readBuffer, 0, read));
            }
            throw new RuntimeException("oh dear");
            //return new BurpHTTPRequest(service, req, response.toByteArray());
        } catch (Exception e) {
            Utilities.out("oh dear");
            return null;
        }
    }

    static byte[] convertToHttp1(byte[] req) {
        // ISO-8859-1 has a 1-to-1 encoding between strings and bytes
        String tmp = new String(req, StandardCharsets.ISO_8859_1);
        tmp = tmp.replaceFirst("HTTP/2", "HTTP/1.1");
        return tmp.getBytes(StandardCharsets.ISO_8859_1);
    }

    static IHttpRequestResponse attemptRequest(IHttpService service, byte[] req) {
        return attemptRequest(service, req, false);
    }

    static IHttpRequestResponse attemptRequest(IHttpService service, byte[] req, boolean forceHttp1) {
        if(unloaded.get()) {
            Utilities.out("Extension unloaded - aborting attack");
            throw new RuntimeException("Extension unloaded");
        }

        boolean LOG_PERFORMANCE = false;
        boolean GO_ACCELERATOR = false;
        IHttpRequestResponse result = null;
        long start = 0;
        int maxAttempts = 3;

        boolean expectNestedResponse = false;
        if (chopNestedResponses && "1".equals(Utilities.getHeader(req, "X-Mine-Nested-Request")) /*Utilities.containsBytes(Utilities.getBodyBytes(req), " HTTP/1.1\r\n".getBytes())*/) {
            expectNestedResponse = true;
            maxAttempts = Utilities.globalSettings.getInt("tunnelling retry count");
        }

        for(int attempt=1; attempt<maxAttempts; attempt++) {
            try {
                if (LOG_PERFORMANCE) {
                    requestCount.incrementAndGet();
                    start = System.currentTimeMillis();
                }

                if (GO_ACCELERATOR) {
                    result = fetchWithGo(service, req);
                }
                else {
                    result = callbacks.makeHttpRequest(service, req, forceHttp1);
                }

            } catch(RuntimeException e) {
                Utilities.log(e.toString());
                Utilities.log("Critical request error, retrying...");
                continue;
            }

            if (result.getResponse() == null) {
                Utilities.log("Req failed, retrying...");
                continue;
                //requestResponse.setResponse(new byte[0]);
            }

            if (expectNestedResponse) {
                byte[] nestedResponse = Utilities.getNestedResponse(result.getResponse());
                result.setResponse(nestedResponse);
                if (nestedResponse == null) {
                    continue;
                }
            }

            if (LOG_PERFORMANCE) {
                long duration = System.currentTimeMillis() - start;
                Utilities.out("Time: "+duration);
                //requestTimes.add(duration);
            }
            break;

        }

        if (result == null || result.getResponse() == null) {
            if (expectNestedResponse) {
                if (Utilities.globalSettings.getBoolean("abort on tunnel failure")) {
                    throw new RuntimeException("Failed to get a nested response after " + maxAttempts + " retries. Bailing!");
                } else {
                    Utilities.out("Failed to get a nested response after " + maxAttempts + " retries. Continuing with null response.");
                }
            }
            else {
                Utilities.log("Req failed multiple times, giving up");
            }
        }

        return result;
    }

    static byte[] getNestedResponse(byte[] response) {
        byte[] body = getBodyBytes(response);
        if (!containsBytes(body, "HTTP/".getBytes())) {
            return null;
        }
        int nestedRespStart = helpers.indexOf(body, "HTTP/".getBytes(), true, 0, body.length);
        return Arrays.copyOfRange(body, nestedRespStart, body.length);
    }

    static String encodeParam(String payload) {
        return payload.replace("%", "%25").replace("\u0000", "%00").replace("&", "%26").replace("#", "%23").replace("\u0020", "%20").replace(";", "%3b").replace("+", "%2b").replace("\n", "%0A").replace("\r", "%0d");
    }


    static byte[] addCacheBuster(byte[] req, String cacheBuster) {
        if (cacheBuster != null) {
            req = Utilities.appendToQuery(req, cacheBuster + "=1");
        } else {
            cacheBuster = Utilities.generateCanary();
        }

        if (globalSettings.getBoolean("include origin in cachebusters")) {
            req = Utilities.addOrReplaceHeader(req, "Origin", "https://" + cacheBuster + ".com");
        }

        if (globalSettings.getBoolean("include path in cachebusters")) {
            String path = Utilities.getPathFromRequest(req);
            path = "/"+cacheBuster+"/.."+path;
            req = Utilities.setPath(req, path);
        }

        req = Utilities.appendToHeader(req, "Accept", ", text/" + cacheBuster);
        req = Utilities.appendToHeader(req, "Accept-Encoding", ", " + cacheBuster);
        req = Utilities.appendToHeader(req, "User-Agent", " " + cacheBuster);

        return req;
    }

    static boolean isHTTPS(IHttpService service) {
        return service.getProtocol().toLowerCase().contains("https");
    }

    static IRequestInfo analyzeRequest(byte[] request) {
        return analyzeRequest(request, null);
    }

    static IRequestInfo analyzeRequest(IHttpRequestResponse request) {
        return analyzeRequest(request.getRequest(), request.getHttpService());
    }

    static IRequestInfo analyzeRequest(byte[] request, IHttpService service) {
        return new LazyRequestInfo(request, service);
    }

    static IScanIssue reportReflectionIssue(Attack[] attacks, IHttpRequestResponse baseRequestResponse) {
        return reportReflectionIssue(attacks, baseRequestResponse, "", "");
    }

    static IScanIssue reportReflectionIssue(Attack[] attacks, IHttpRequestResponse baseRequestResponse, String title) {
        return reportReflectionIssue(attacks, baseRequestResponse, title, "");
    }

    static IScanIssue reportReflectionIssue(Attack[] attacks, IHttpRequestResponse baseRequestResponse, String title, String detail) {
        IHttpRequestResponse[] requests = new IHttpRequestResponse[attacks.length];
        Probe bestProbe = null;
        boolean reliable = false;
        detail = detail + "<br/><br/><b>Successful probes</b><br/>";
        String reportedSeverity = "High";
        int evidenceCount = 0;

        for (int i=0; i<attacks.length; i++) {
            requests[i] = attacks[i].getLastRequest(); // was getFirstRequest
            if (i % 2 == 0) {
                detail += " &#160;  &#160; <table><tr><td><b>"+StringEscapeUtils.escapeHtml4(attacks[i].getProbe().getName())+" &#160;  &#160; </b></td><td><b>"+ StringEscapeUtils.escapeHtml4(attacks[i].payload)+ " &#160; </b></td><td><b>";
            }
            else {
                detail += StringEscapeUtils.escapeHtml4(attacks[i].payload)+"</b></td></tr>\n";
                HashMap<String, Object> workedPrint = attacks[i].getLastPrint(); // was getFirstPrint
                HashMap<String, Object> consistentWorkedPrint = attacks[i].getPrint();
                HashMap<String, Object> breakPrint = attacks[i-1].getLastPrint(); // was getFirstPrint
                HashMap<String, Object> consistentBreakPrint = attacks[i-1].getPrint();

                Set<String> allKeys = new HashSet<>(consistentWorkedPrint.keySet());
                allKeys.addAll(consistentBreakPrint.keySet());
                String boringDetail = "";

                for (String mark: allKeys) {
                    String brokeResult = breakPrint.get(mark).toString();
                    String workedResult = workedPrint.get(mark).toString();

                    if(brokeResult.equals(workedResult)) {
                        continue;
                    }

                    evidenceCount++;

                    try {
                        if (Math.abs(Integer.parseInt(brokeResult)) > 9999) {
                            brokeResult = "X";
                        }
                        if (Math.abs(Integer.parseInt(workedResult)) > 9999) {
                            workedResult = "Y";
                        }
                    }
                    catch (NumberFormatException e) {
                        brokeResult = StringEscapeUtils.escapeHtml4(brokeResult);
                        workedResult = StringEscapeUtils.escapeHtml4(workedResult);
                    }

                    if (consistentBreakPrint.containsKey(mark) && consistentWorkedPrint.containsKey(mark)) {
                        detail += "<tr><td>" + StringEscapeUtils.escapeHtml4(mark) + "</td><td>" + "" + brokeResult + " </td><td>" + workedResult + "</td></tr>\n";
                        reliable = true;
                    }
                    else if (consistentBreakPrint.containsKey(mark)) {
                        boringDetail += "<tr><td><i>" + StringEscapeUtils.escapeHtml4(mark)+"</i></td><td><i>" + brokeResult + "</i></td><td><i> *" + workedResult + "*</i></td></tr>\n";
                    }
                    else {
                        boringDetail += "<tr><td><i>" + StringEscapeUtils.escapeHtml4(mark)+"</i></td><td><i>*" + brokeResult + "*</i></td><td><i>" + workedResult + "</i></td></tr>\n";
                    }

                }
                detail += boringDetail;
                detail += "</table>\n";

                String tip = attacks[i].getProbe().getTip();
                if (!"".equals(tip)) {
                    detail += "&nbsp;<i>"+tip+"</i>";
                }
            }

            if (bestProbe == null || attacks[i].getProbe().getSeverity() >= bestProbe.getSeverity()) {
                bestProbe = attacks[i].getProbe();

                int severity = bestProbe.getSeverity();
                if (severity < 3) {
                    reportedSeverity = "Low";
                }
                else if (severity < 7) {
                    reportedSeverity = "Medium";
                }

            }
        }

        if (evidenceCount == 1) {
            reportedSeverity = "Information";
        }

        if ("Interesting input handling".equals(title)) {
            title = bestProbe.getName();
        }

        return new Fuzzable(requests, baseRequestResponse, title, detail, reliable, reportedSeverity); //attacks[attacks.length-2].getProbe().getName()
    }
}


class Fuzzable extends CustomScanIssue {

    private final static String REMEDIATION = "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. Try to determine the root cause of the observed behaviour." +
            "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results. ";

    Fuzzable(IHttpRequestResponse[] requests, IHttpRequestResponse baseRequestResponse, String title, String detail, boolean reliable, String severity) {
        super(requests[0].getHttpService(), Utilities.analyzeRequest(baseRequestResponse).getUrl(), ArrayUtils.add(requests, 0, baseRequestResponse), title, detail, severity, calculateConfidence(reliable), REMEDIATION);
    }

    private static String calculateConfidence(boolean reliable) {
        String confidence = "Tentative";
        if (reliable) {
            confidence = "Firm";
        }
        return confidence;
    }

}



