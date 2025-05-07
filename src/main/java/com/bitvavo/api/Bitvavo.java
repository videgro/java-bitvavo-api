package com.bitvavo.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class Bitvavo {
  private static final Logger LOGGER = LogManager.getLogger();

  private String apiKey;
  private String apiSecret;
  private long operatorId;
  private String restUrl;
  private String wsUrl;
  private boolean authenticated;
  private boolean debugging = true;
  private int window;
  private WebsocketClientEndpoint ws;
  private Websocket websocketObject;
  private KeepAliveThread keepAliveThread;
  private Map<String, Object> book;
  private boolean activatedSubscriptionTicker;
  private boolean activatedSubscriptionTicker24h;
  private boolean activatedSubscriptionAccount;
  private boolean activatedSubscriptionCandles;
  private boolean activatedSubscriptionTrades;
  private boolean activatedSubscriptionBookUpdate;
  private boolean activatedSubscriptionBook;
  private JSONObject optionsSubscriptionTicker;
  private JSONObject optionsSubscriptionTicker24h;
  private JSONObject optionsSubscriptionAccount;
  private JSONObject optionsSubscriptionCandles;
  private JSONObject optionsSubscriptionTrades;
  private JSONObject optionsSubscriptionBookUpdate;
  private JSONObject optionsSubscriptionBookFirst;
  private JSONObject optionsSubscriptionBookSecond;
  private volatile int rateLimitRemaining = 1000;
  private volatile long rateLimitReset = 0;
  private volatile boolean rateLimitThreadStarted = false;

  public Bitvavo(final JSONObject options) {
    final JSONArray keys = options.names();
    boolean apiKeySet = false;
    boolean apiSecretSet = false;
    boolean operatorIdSet = false;
    boolean windowSet = false;
    boolean debuggingSet = false;
    boolean restUrlSet = false;
    boolean wsUrlSet = false;
    for (int i = 0; i < keys.length(); ++i) {
      final String key = keys.getString(i);
      if(key.equalsIgnoreCase("apikey")) {
        apiKey = options.getString(key);
        apiKeySet = true;
      } else if(key.equalsIgnoreCase("apisecret")) {
        apiSecret = options.getString(key);
        apiSecretSet = true;
      } else if(key.equalsIgnoreCase("operatorid")) {
        operatorId = options.getLong(key);
        operatorIdSet = true;
      } else if(key.equalsIgnoreCase("accesswindow")) {
        window = options.getInt(key);
        windowSet = true;
      } else if(key.equalsIgnoreCase("debugging")) {
        debugging = options.getBoolean(key);
        debuggingSet = true;
      } else if(key.equalsIgnoreCase("resturl")) {
        restUrl = options.getString(key);
        restUrlSet = true;
      } else if(key.equalsIgnoreCase("wsurl")) {
        wsUrl = options.getString(key);
        wsUrlSet = true;
      }
    }
    if (!apiKeySet) {
      apiKey = "";
    }
    if (!apiSecretSet) {
      apiSecret = "";
    }
    if (!operatorIdSet) {
      operatorId = 1;
    }
    if (!windowSet) {
      window = 10000;
    }
    if (!debuggingSet) {
      debugging = false;
    }
    if (!restUrlSet) {
      restUrl = "https://api.bitvavo.com/v2";
    }
    if (!wsUrlSet) {
      wsUrl = "wss://ws.bitvavo.com/v2/";
    }
  }

  public String getApiKey() {
    return this.apiKey;
  }

  public String getApiSecret() {
    return this.apiSecret;
  }

  private String createPostfix(final JSONObject options) {
    final ArrayList<String> array = new ArrayList<>();
    final Iterator<?> keys = options.keys();
    while(keys.hasNext()) {
      final String key = (String) keys.next();
      array.add(key + "=" + options.get(key).toString());
    }
    String params = String.join("&", array);
    if(options.length() > 0) {
      params = "?" + params;
    }
    return params;
  }

  public String createSignature(final long timestamp,final String method,final String urlEndpoint,final JSONObject body) {
    if (apiSecret == null || apiKey == null) {
      LOGGER.error("The API key or secret has not been set. Please pass the key and secret when instantiating the bitvavo object.");
      return "";
    }
    try {
      String result = String.valueOf(timestamp) + method + "/v2" + urlEndpoint;
      if(body.length() != 0) {
        result = result + body.toString();
      }
      final Mac sha256HMAC = Mac.getInstance("HmacSHA256");
      final SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      sha256HMAC.init(secretKey);
      return new String(Hex.encodeHex(sha256HMAC.doFinal(result.getBytes(StandardCharsets.UTF_8))));
    } catch(NoSuchAlgorithmException | InvalidKeyException e) {
      LOGGER.error("Caught exception in createSignature",e);
      return "";
    }
 }

  private void debug(final String message) {
    if(debugging && LOGGER.isDebugEnabled())  {
      LOGGER.debug(message);
    }
  }

  public void errorRateLimit(final JSONObject response) {
    if (response.getInt("errorCode") == 105) {
      rateLimitRemaining = 0;
      final String message = response.getString("error");
      final String placeHolder = message.split(" at ")[1].replace(".", "");
      rateLimitReset = Long.parseLong(placeHolder);
      if (!rateLimitThreadStarted) {
        new Thread(() -> {
            try {
              long timeToWait = rateLimitReset - System.currentTimeMillis();
              rateLimitThreadStarted = true;
              debug("We are waiting for " + ((int) timeToWait / 1000) + " seconds, untill the rate limit ban will be lifted.");
              Thread.sleep(timeToWait);
            } catch (InterruptedException e) {
              LOGGER.error("Got interrupted while waiting for the rate limit ban to be lifted.",e);
            }
            rateLimitThreadStarted = false;
            if (System.currentTimeMillis() >= rateLimitReset) {
              debug("Rate limit ban has been lifted, resetting rate limit to 1000.");
              rateLimitRemaining = 1000;
            }
        }).start();
      }
    }
  }

  public void updateRateLimit(final Map<String,List<String>> response) {
    final String remainingHeader = response.get("bitvavo-ratelimit-remaining").get(0);
    final String resetHeader = response.get("bitvavo-ratelimit-resetat").get(0);
    if (remainingHeader != null) {
      rateLimitRemaining = Integer.parseInt(remainingHeader);
    }
    if (resetHeader != null) {
      rateLimitReset = Long.parseLong(resetHeader);
      if (!rateLimitThreadStarted) {
        new Thread(() -> {
            try {
              final long timeToWait = rateLimitReset - System.currentTimeMillis();
              rateLimitThreadStarted = true;
              debug("We started a thread which waits for " + ((int) timeToWait / 1000) + " seconds, untill the rate limit will be reset.");
              Thread.sleep(timeToWait);
            } catch (InterruptedException e) {
              LOGGER.error("Got interrupted while waiting for the rate limit to be reset.",e);
            }
            rateLimitThreadStarted = false;
            if (System.currentTimeMillis() >= rateLimitReset) {
              debug("Resetting rate limit to 1000.");
              rateLimitRemaining = 1000;
            }
        }).start();
      }
    }
  }

  public int getRemainingLimit() {
    return rateLimitRemaining;
  }

  private JSONObject privateRequest(final String urlEndpoint,final String urlParams,final String method,final JSONObject body) {
    try {
      final long timestamp = System.currentTimeMillis();
      final String signature = createSignature(timestamp, method, (urlEndpoint + urlParams), body);
      final URL url = new URL(this.restUrl + urlEndpoint + urlParams);
      final HttpsURLConnection httpsCon = (HttpsURLConnection) url.openConnection();

      httpsCon.setRequestMethod(method);
      httpsCon.setRequestProperty("bitvavo-access-key", apiKey);
      httpsCon.setRequestProperty("bitvavo-access-signature", signature);
      httpsCon.setRequestProperty("bitvavo-access-timestamp", String.valueOf(timestamp));
      httpsCon.setRequestProperty("bitvavo-access-window", String.valueOf(window));
      httpsCon.setRequestProperty("content-type", "application/json");
      if(body.length() != 0) {
        httpsCon.setDoOutput(true);
        final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(httpsCon.getOutputStream());
        outputStreamWriter.write(body.toString());
        outputStreamWriter.flush();
      }

      int responseCode = httpsCon.getResponseCode();

      InputStream inputStream;
      if(responseCode == 200) {
        inputStream = httpsCon.getInputStream();
        updateRateLimit(httpsCon.getHeaderFields());
      } else {
        inputStream = httpsCon.getErrorStream();
      }
      final StringWriter writer = new StringWriter();
      IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
      final String result = writer.toString();

      final JSONObject response = new JSONObject(result);
      if (result.contains("errorCode")) {
        errorRateLimit(response);
      }
      return response;
    } catch (final IOException e) {
      LOGGER.error("Caught exception in privateRequest",e);
      return new JSONObject();
    }
  }

  private JSONArray privateRequestArray(final String urlEndpoint,final String urlParams,final String method,final JSONObject body) {
    try {
      final long timestamp = System.currentTimeMillis();
      final String signature = createSignature(timestamp, method, (urlEndpoint + urlParams), body);
      final URL url = new URL(restUrl + urlEndpoint + urlParams);
      final HttpsURLConnection httpsCon = (HttpsURLConnection) url.openConnection();

      httpsCon.setRequestMethod(method);
      httpsCon.setRequestProperty("bitvavo-access-key", apiKey);
      httpsCon.setRequestProperty("bitvavo-access-signature", signature);
      httpsCon.setRequestProperty("bitvavo-access-timestamp", String.valueOf(timestamp));
      httpsCon.setRequestProperty("bitvavo-access-window", String.valueOf(window));
      httpsCon.setRequestProperty("content-type", "application/json");
      if(body.length() != 0) {
        httpsCon.setDoOutput(true);
        final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(httpsCon.getOutputStream());
        outputStreamWriter.write(body.toString());
        outputStreamWriter.flush();
      }

      int responseCode = httpsCon.getResponseCode();

      InputStream inputStream;
      if (responseCode == 200) {
        inputStream = httpsCon.getInputStream();
        updateRateLimit(httpsCon.getHeaderFields());
      } else {
        inputStream = httpsCon.getErrorStream();
      }

      final StringWriter writer = new StringWriter();
      IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
      String result = writer.toString();

      if(result.contains("errorCode")) {
        errorRateLimit(new JSONObject(result));
        LOGGER.error(result);
        return new JSONArray();
      }

      return new JSONArray(result);
    } catch(final IOException e) {
      LOGGER.error("Caught exception in privateRequestArray",e);
      return new JSONArray();
    }
  }

  public JSONObject publicRequest(final String urlString,final String method) {
    try {
      final URL url = new URL(urlString);
      final HttpsURLConnection httpsCon = (HttpsURLConnection) url.openConnection();
      httpsCon.setRequestMethod(method);
      if (!apiKey.isEmpty()) {
        final long timestamp = System.currentTimeMillis();
        final String signature = createSignature(timestamp, method, urlString.replace(this.restUrl, ""), new JSONObject());
        httpsCon.setRequestProperty("bitvavo-access-key", this.apiKey);
        httpsCon.setRequestProperty("bitvavo-access-signature", signature);
        httpsCon.setRequestProperty("bitvavo-access-timestamp", String.valueOf(timestamp));
        httpsCon.setRequestProperty("bitvavo-access-window", String.valueOf(this.window));
        httpsCon.setRequestProperty("content-type", "application/json");
      }
      final int responseCode = httpsCon.getResponseCode();
      InputStream inputStream;
      if(responseCode == 200) {
        inputStream = httpsCon.getInputStream();
        updateRateLimit(httpsCon.getHeaderFields());
      } else {
        inputStream = httpsCon.getErrorStream();
      }
      final StringWriter writer = new StringWriter();
      IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
      final String result = writer.toString();

      final JSONObject response = new JSONObject(result);
      if (result.contains("errorCode")) {
        errorRateLimit(response);
      }
      return response;
    } catch(final IOException e) {
      LOGGER.error("publicRequest",e);
    }
    return new JSONObject("{}");
  }

  public JSONArray publicRequestArray(final String urlString,final String method) {
    try {
      URL url = new URL(urlString);
      HttpsURLConnection httpsCon = (HttpsURLConnection) url.openConnection();
      httpsCon.setRequestMethod(method);
      if (!apiKey.isEmpty()) {
        final long timestamp = System.currentTimeMillis();
        final String signature = createSignature(timestamp, method, urlString.replace(this.restUrl, ""), new JSONObject());
        httpsCon.setRequestProperty("bitvavo-access-key", this.apiKey);
        httpsCon.setRequestProperty("bitvavo-access-signature", signature);
        httpsCon.setRequestProperty("bitvavo-access-timestamp", String.valueOf(timestamp));
        httpsCon.setRequestProperty("bitvavo-access-window", String.valueOf(this.window));
        httpsCon.setRequestProperty("content-type", "application/json");
      }
      int responseCode = httpsCon.getResponseCode();
      InputStream inputStream;
      if(responseCode == 200) {
        inputStream = httpsCon.getInputStream();
        updateRateLimit(httpsCon.getHeaderFields());
      } else {
        inputStream = httpsCon.getErrorStream();
      }

      final StringWriter writer = new StringWriter();
      IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
      final String result = writer.toString();
      if(result.indexOf("error") != -1) {
        errorRateLimit(new JSONObject(result));
        return new JSONArray("[" + result + "]");
      }
      debug("FULL RESPONSE: " + result);

      return new JSONArray(result);
    } catch(final IOException e) {
      LOGGER.error("publicRequestArray",e);
    }
    return new JSONArray("[{}]");
  }

  /**
   * Returns the current time in unix time format (milliseconds since 1 jan 1970)
   * @return JSONObject response, get time through response.getLong("time")
   */
  public JSONObject time() {
    return publicRequest((this.restUrl + "/time"), "GET");
  }

  /**
   * Returns the available markets
   * @param options optional parameters: market
   * @return JSONArray response, get markets by iterating over array: response.get(index)
   */
  public JSONArray markets(final JSONObject options) {
    final String postfix = createPostfix(options);
    if(options.has("market")) {
      final JSONArray returnArray = new JSONArray();
      returnArray.put(publicRequest((restUrl + "/markets" + postfix), "GET"));
      return returnArray;
    } else {
      return publicRequestArray((restUrl + "/markets" + postfix), "GET");
    }
  }

  /**
   * Returns the available assets
   * @param options optional parameters: symbol
   * @return JSONArray response, get assets by iterating over array response.get(index)
   */
  public JSONArray assets(final JSONObject options) {
    final String postfix = createPostfix(options);
    if(options.has("symbol")) {
      final JSONArray returnArray = new JSONArray();
      returnArray.put(publicRequest((restUrl + "/assets" + postfix), "GET"));
      return returnArray;
    } else {
      return publicRequestArray((restUrl + "/assets" + postfix), "GET");
    }
  }

  /**
   * Returns the book for a certain market
   * @param market Specifies the market for which the book should be returned.
   * @param options optional parameters: depth
   * @return JSONObject response, get bids through response.getJSONArray("bids"), asks through response.getJSONArray("asks")
   */
  public JSONObject book(final String market,final JSONObject options) {
    final String postfix = createPostfix(options);
    return publicRequest((restUrl + "/" + market + "/book" + postfix), "GET");
  }

  /**
   * Returns the trades for a specific market
   * @param market Specifies the market for which trades should be returned
   * @param options optional parameters: limit, start, end, tradeIdFrom, tradeIdTo
   * @return JSONArray response, iterate over array to get individual trades response.getJSONObject(index)
   */
  public JSONArray publicTrades(final String market,final JSONObject options) {
    final String postfix = createPostfix(options);
    return publicRequestArray((restUrl + "/" + market + "/trades" + postfix), "GET");
  }

  /**
   *  Returns the candles for a specific market
   * @param market market for which the candles should be returned
   * @param interval interval on which the candles should be returned
   * @param options optional parameters: limit, start, end
   * @return JSONArray response, get individual candles through response.getJSONArray(index)
   */
  public JSONArray candles(final String market,final String interval,final JSONObject options) {
    options.put("interval", interval);
    final String postfix = createPostfix(options);
    return publicRequestArray((restUrl + "/" + market + "/candles" + postfix), "GET");
  }

  /**
   * Returns the ticker price
   * @param options optional parameters: market
   * @return JSONArray response, get individual prices by iterating over array: response.getJSONObject(index)
   */
  public JSONArray tickerPrice(final JSONObject options) {
    final String postfix = createPostfix(options);
    if(options.has("market")) {
      final JSONArray returnArray = new JSONArray();
      returnArray.put(publicRequest((restUrl + "/ticker/price" + postfix), "GET"));
      return returnArray;
    } else {
      return publicRequestArray((restUrl + "/ticker/price" + postfix), "GET");
    }
  }

  /**
   * Return the book ticker
   * @param options optional parameters: market
   * @return JSONArray response, get individual books by iterating over array: response.getJSONObject(index)
   */
  public JSONArray tickerBook(final JSONObject options) {
    final String postfix = createPostfix(options);
    if(options.has("market")) {
      final JSONArray returnArray = new JSONArray();
      returnArray.put(publicRequest((restUrl + "/ticker/book" + postfix), "GET"));
      return returnArray;
    } else {
      return publicRequestArray((restUrl + "/ticker/book" + postfix), "GET"); 
    }
  }

  /**
   * Return the 24 hour ticker
   * @param options optional parameters: market
   * @return JSONArray response, get individual 24 hour prices by iterating over array: response.getJSONObject(index)
   */
  public JSONArray ticker24h(final JSONObject options) {
    final String postfix = createPostfix(options);
    if(options.has("market")) {
      final JSONArray returnArray = new JSONArray();
      returnArray.put(publicRequest((restUrl + "/ticker/24h" + postfix), "GET"));
      return returnArray;
    } else {
      return publicRequestArray((restUrl + "/ticker/24h" + postfix), "GET"); 
    }
  }

  /**
   * Places an order on the exchange
   * @param market The market for which the order should be created
   * @param side is this a buy or sell order
   * @param orderType is this a limit or market order
   * @param body optional body parameters: limit:(amount, price, postOnly), market:(amount, amountQuote, disableMarketProtection)
   *                                       stopLoss/takeProfit:(amount, amountQuote, disableMarketProtection, triggerType, triggerReference, triggerAmount)
   *                                       stopLossLimit/takeProfitLimit:(amount, price, postOnly, triggerType, triggerReference, triggerAmount)
   *                                       all orderTypes: timeInForce, selfTradePrevention, responseRequired
   * @return JSONObject response, get status of the order through response.getString("status")
   */
  public JSONObject placeOrder(final String market,final String side,final String orderType,final JSONObject body) {
    body.put("market", market);
    body.put("side", side);
    body.put("orderType", orderType);
    body.put("operatorId", operatorId);
    return privateRequest("/order", "", "POST", body);
  }

  /**
   * Returns a specific order
   * @param market the market the order resides on
   * @param orderId the id of the order
   * @return JSONObject response, get status of the order through response.getString("status")
   */
  public JSONObject getOrder(final String market,final String orderId) {
    final JSONObject options = new JSONObject();
    options.put("market", market);
    options.put("orderId", orderId);
    final String postfix = createPostfix(options);
    return privateRequest("/order", postfix, "GET", new JSONObject());
  }

  /**
   * Updates an order
   * @param market the market the order resides on
   * @param orderId the id of the order which should be updated
   * @param body optional body parameters: limit:(amount, amountRemaining, price, timeInForce, selfTradePrevention, postOnly)
   *                           untriggered stopLoss/takeProfit:(amount, amountQuote, disableMarketProtection, triggerType, triggerReference, triggerAmount)
   *                                       stopLossLimit/takeProfitLimit: (amount, price, postOnly, triggerType, triggerReference, triggerAmount)
   * @return JSONObject response, get status of the order through response.getString("status")
   */
  public JSONObject updateOrder(final String market,final String orderId,final JSONObject body) {
    body.put("market", market);
    body.put("orderId", orderId);
    body.put("operatorId", operatorId);
    return privateRequest("/order", "", "PUT", body);
  }

  /**
   * Cancel an order
   * @param market the market the order resides on
   * @param orderId the id of the order which should be cancelled
   * @return JSONObject response, get the id of the order which was cancelled through response.getString("orderId")
   */
  public JSONObject cancelOrder(final String market,final String orderId) {
    final JSONObject options = new JSONObject();
    options.put("market", market);
    options.put("orderId", orderId);
    options.put("operatorId", operatorId);
    final String postfix = createPostfix(options);
    return privateRequest("/order", postfix, "DELETE", new JSONObject());
  }

  /**
   * Returns multiple orders for a specific market
   * @param market the market for which orders should be returned
   * @param options optional parameters: limit, start, end, orderIdFrom, orderIdTo
   * @return JSONArray response, get individual orders by iterating over array: response.getJSONObject(index)
   */
  public JSONArray getOrders(final String market,final JSONObject options) {
    options.put("market", market);
    final String postfix = createPostfix(options);
    return privateRequestArray("/orders", postfix, "GET", new JSONObject());
  }

  /**
   * Cancel multiple orders at once, if no market is specified all orders will be canceled
   * @param options optional parameters: market
   * @return JSONArray response, get individual cancelled orderId's by iterating over array: response.getJSONObject(index).getString("orderId")
   */
  public JSONArray cancelOrders(final JSONObject options) {
    final String postfix = createPostfix(options);
    return privateRequestArray("/orders", postfix, "DELETE", new JSONObject());
  }

  /**
   * Returns all open orders for an account
   * @param options optional parameters: market
   * @return JSONArray response, get individual orders by iterating over array: response.getJSONObject(index)
   */
  public JSONArray ordersOpen(final JSONObject options) {
    final String postfix = createPostfix(options);
    return privateRequestArray("/ordersOpen", postfix, "GET", new JSONObject());
  }

  /**
   * Returns all trades for a specific market
   * @param market the market for which trades should be returned
   * @param options optional parameters: limit, start, end, tradeIdFrom, tradeIdTo
   * @return JSONArray trades, get individual trades by iterating over array: response.getJSONObject(index)
   */
  public JSONArray trades(final String market,final JSONObject options) {
    options.put("market", market);
    final String postfix = createPostfix(options);
    return privateRequestArray("/trades", postfix, "GET", new JSONObject());
  }

  /**
   * Return the fee tier for an account
   * @return JSONObject response, get taker fee through: response.getJSONObject("fees").getString("taker")
   */
  public JSONObject account() {
    return privateRequest("/account", "", "GET", new JSONObject());
  }

  /**
   * Returns the balance for an account
   * @param options optional parameters: symbol
   * @return JSONArray response, get individual balances by iterating over array: response.getJSONObject(index)
   */
  public JSONArray balance(final JSONObject options) {
    final String postfix = createPostfix(options);
    return privateRequestArray("/balance", postfix, "GET", new JSONObject());
  }

  /**
   * Returns the deposit address which can be used to increase the account balance
   * @param symbol the crypto currency for which the address should be returned
   * @return JSONObject response, get address through response.getString("address")
   */
  public JSONObject depositAssets(final String symbol) {
    final JSONObject options = new JSONObject();
    options.put("symbol", symbol);
    final String postfix = createPostfix(options);
    return privateRequest("/deposit", postfix, "GET", new JSONObject());
  }

  /**
   * Creates a withdrawal to another address
   * @param symbol the crypto currency for which the withdrawal should be created
   * @param amount the amount which should be withdrawn
   * @param address The address to which the crypto should get sent
   * @param body optional parameters: paymentId, internal, addWithdrawalFee
   * @return JSONObject response, get success confirmation through response.getBoolean("success")
   */
  public JSONObject withdrawAssets(final String symbol,final String amount,final String address,final JSONObject body) {
    body.put("symbol", symbol);
    body.put("amount", amount);
    body.put("address", address);
    return privateRequest("/withdrawal", "", "POST", body);
  }

  /**
   * Returns the entire deposit history for an account
   * @param options optional parameters: symbol, limit, start, end
   * @return JSONArray response, get individual deposits by iterating over the array: response.getJSONObject(index)
   */
  public JSONArray depositHistory(final JSONObject options) {
    final String postfix = createPostfix(options);
    return privateRequestArray("/depositHistory", postfix, "GET", new JSONObject());
  }

  /**
   * Returns the entire withdrawal history for an account
   * @param options optional parameters: symbol, limit, start, end
   * @return JSONArray response, get individual withdrawals by iterating over the array: response.getJSONObject(index)
   */
  public JSONArray withdrawalHistory(final JSONObject options) {
    final String postfix = createPostfix(options);
    return privateRequestArray("/withdrawalHistory", postfix, "GET", new JSONObject());
  }

  /**
   * Creates a websocket object
   * @return Websocket the object on which all websocket function can be called.
   */
  public Websocket newWebsocket() {
    websocketObject = new Websocket();
    return websocketObject;
  }

  public class Websocket {
    public Websocket() {
      try {
        final WebsocketClientEndpoint clientEndPoint = new WebsocketClientEndpoint(new URI(wsUrl), Bitvavo.this);
        clientEndPoint.addAuthenticateHandler(response -> {
            if(response.has("authenticated")) {
              authenticated = true;
              debug("We registered authenticated as true");
            }
        });
        clientEndPoint.addMessageHandler(response -> LOGGER.error("Unexpected message: {}",response));
        ws = clientEndPoint;
        book = new HashMap<>();
        keepAliveThread = new KeepAliveThread();
        keepAliveThread.start();
      } catch (final URISyntaxException e) {
        LOGGER.error("Caught exception in websocket",e);
      }
    }

    void handleBook(final Runnable function) {
      function.run();
    }

    public void close() {
      ws.closeSocket();
    }

    public void doSendPublic(final JSONObject options) {
      ws.sendMessage(options.toString());
    }

    public void doSendPrivate(final JSONObject options) {
      if(getApiKey() == null) {
        LOGGER.error("You forgot to set the key and secret, both are required for this functionality.");
      } else if(authenticated) {
        ws.sendMessage(options.toString());
      } else {
        try {
          TimeUnit.MILLISECONDS.sleep(50);
          doSendPrivate(options);
        } catch (final InterruptedException e) {
          LOGGER.error("Interrupted, aborting send.",e);
        }
      }
    }

    /**
     * Sets the callback for errors
     * @param msgHandler callback
     */
    public void setErrorCallback(final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addErrorHandler(msgHandler);
    }

    /**
   * Returns the current time in unix timestamp (milliseconds since 1 jan 1970).
   *@param msgHandler callback
   * @return JSONObject response, get time through response.getJSONObject("response").getLong("time")
   */
    public void time(final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addTimeHandler(msgHandler);
      doSendPublic(new JSONObject("{ action: getTime }"));
    }

    /**
   * Returns available markets.
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get markets through response.getJSONArray("response") and iterate over array to get objects array.getJSONObject(index)
   */
    public void markets(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addMarketsHandler(msgHandler);
      options.put("action", "getMarkets");
      doSendPublic(options);
    }

  /**
   * Returns available assets.
   *
   * @param options optional parameters: symbol
   * @param msgHandler callback
   * @return JSONObject response, get assets through response.getJSONArray("response") and iterate over array to get objects array.getJSONObject(index)
   */
    public void assets(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addAssetsHandler(msgHandler);
      options.put("action", "getAssets");
      doSendPublic(options);
    }

  /**
   * Returns the book per market.
   *
   * @param market market for which the book should be returned.
   * @param options optional parameters: depth
   * @param msgHandler callback
   * @return JSONObject response, get book through response.getJSONObject("response") and get individual values through object.getJSONArray("bids"/"asks").get(index).get(index)
   */
    public void book(final String market,final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addBookHandler(msgHandler);
      options.put("action", "getBook");
      options.put("market", market);
      doSendPublic(options);
    }

   /**
   * Returns the trades per market.
   *
   * @param market market for which the trades should be returned.
   * @param options optional parameters: limit, start, end, tradeIdFrom, tradeIdTo
   * @param msgHandler callback
   * @return JSONObject response, get trades through response.getJSONArray("response") and iterate over array to get objects array.getJSONObject(index)
   */
    public void publicTrades(final String market,final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addTradesHandler(msgHandler);
      options.put("action", "getTrades");
      options.put("market", market);
      doSendPublic(options);
    }

   /**
   * Returns the candles per market.
   *
   * @param market market for which the candles should be returned.
   * @param interval interval for which the candles should be returned.
   * @param options optional parameters: limit, start, end
   * @param msgHandler callback
   * @return JSONObject response, get candles through response.getJSONArray("response") and iterate over array to get arrays containing timestamp, open, high, low, close and volume: array.getJSONArray(index) (i.e. for the open price array.getJSONArray(index).get(1))
   */
    public void candles(final String market,final String interval,final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addCandlesHandler(msgHandler);
      options.put("action", "getCandles");
      options.put("market", market);
      options.put("interval", interval);
      doSendPublic(options);
    }

    /**
   * Returns the 24 hour ticker.
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over array to get 24h ticker objects array.getJSONObject(index)
   */
    public void ticker24h(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addTicker24hHandler(msgHandler);
      options.put("action", "getTicker24h");
      doSendPublic(options);
    }

    /**
   * Returns the price ticker.
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over array to get price ticker objects array.getJSONObject(index)
   */
    public void tickerPrice(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addTickerPriceHandler(msgHandler);
      options.put("action", "getTickerPrice");
      doSendPublic(options);
    }

     /**
   * Returns the price ticker.
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over array to get book ticker objects array.getJSONObject(index)
   */
    public void tickerBook(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addTickerBookHandler(msgHandler);
      options.put("action", "getTickerBook");
      doSendPublic(options);
    }

  /**
   * Places an order.
   *
   * @param market market on which the order should be created
   * @param side is this a sell or buy order
   * @param orderType is this a limit or market order
   * @param body optional body parameters: limit:(amount, price, postOnly), market:(amount, amountQuote, disableMarketProtection)
   *                                       stopLoss/takeProfit:(amount, amountQuote, disableMarketProtection, triggerType, triggerReference, triggerAmount)
   *                                       stopLossLimit/takeProfitLimit:(amount, price, postOnly, triggerType, triggerReference, triggerAmount)
   *                                       all orderTypes: timeInForce, selfTradePrevention, responseRequired
   * @param msgHandler callback
   * @return JSONObject response, get order object through response.getJSONObject("response")
   */
    public void placeOrder(final String market,final String side,final String orderType,final JSONObject body,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addPlaceOrderHandler(msgHandler);
      body.put("market", market);
      body.put("side", side);
      body.put("orderType", orderType);
      body.put("action", "privateCreateOrder");
      doSendPrivate(body);
    }

    /**
   * Returns an order.
   *
   * @param market market on which the order should be returned
   * @param orderId the order which should be returned
   * @param msgHandler callback
   * @return JSONObject response, get order object through response.getJSONObject("response")
   */
    public void getOrder(final String market,final String orderId,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addGetOrderHandler(msgHandler);
      final JSONObject options = new JSONObject();
      options.put("action", "privateGetOrder");
      options.put("market", market);
      options.put("orderId", orderId);
      doSendPrivate(options);
    }

    /**
   * Updates an order.
   *
   * @param market market on which the order should be updated
   * @param orderId the order which should be updated
   * @param body optional body parameters: limit:(amount, amountRemaining, price, timeInForce, selfTradePrevention, postOnly)
   *                           untriggered stopLoss/takeProfit:(amount, amountQuote, disableMarketProtection, triggerType, triggerReference, triggerAmount)
   *                                       stopLossLimit/takeProfitLimit: (amount, price, postOnly, triggerType, triggerReference, triggerAmount)
   * @param msgHandler callback
   * @return JSONObject response, get order object through response.getJSONObject("response")
   */
    public void updateOrder(final String market,final String orderId,final JSONObject body,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addUpdateOrderHandler(msgHandler);
      body.put("market", market);
      body.put("orderId", orderId);
      body.put("action", "privateUpdateOrder");
      doSendPrivate(body);
    }

    /**
   * Cancels an order.
   *
   * @param market market on which the order should be cancelled
   * @param orderId the order which should be cancelled
   * @param msgHandler callback
   * @return JSONObject response, get orderId through response.getJSONObject("response").getString("orderId")
   */
    public void cancelOrder(final String market,final String orderId,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addCancelOrderHandler(msgHandler);
      final JSONObject options = new JSONObject();
      options.put("action", "privateCancelOrder");
      options.put("market", market);
      options.put("orderId", orderId);
      doSendPrivate(options);
    }

  /**
   * Returns multiple orders at once
   *
   * @param market market on which the orders should be returned
   * @param options optional parameters: limit, start, end, orderIdFrom, orderIdTo
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get order objects array.getJSONObject(index)
   */
    public void getOrders(final String market,final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addGetOrdersHandler(msgHandler);
      options.put("action", "privateGetOrders");
      options.put("market", market);
      doSendPrivate(options);
    }

    /**
   * Cancels multiple orders at once
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get orderId's objects array.getJSONObject(index).getString("orderId")
   */
    public void cancelOrders(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addCancelOrdersHandler(msgHandler);
      options.put("action", "privateCancelOrders");
      doSendPrivate(options);
    }

     /**
   * Get all open orders at once
   *
   * @param options optional parameters: market
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get open orders objects array.getJSONObject(index)
   */
    public void ordersOpen(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addGetOrdersOpenHandler(msgHandler);
      options.put("action", "privateGetOrdersOpen");
      doSendPrivate(options);
    }

     /**
   * Returns all trades within a market
   *
   * @param market for which market should the trades be returned
   * @param options optional parameters: limit, start, end, tradeIdFrom, tradeIdTo
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get trades objects array.getJSONObject(index)
   */
    public void trades(final String market,final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addGetTradesHandler(msgHandler);
      options.put("action", "privateGetTrades");
      options.put("market", market);
      doSendPrivate(options);
    }

  /**
   * Returns the fee tier for an account
   *
   * @param msgHandler callback
   * @return JSONObject response, get taker fee through response.getJSONObject("response").getJSONObject("fees").getString("taker")
   */
    public void account(final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addAccountHandler(msgHandler);
      final JSONObject options = new JSONObject("{ action: privateGetAccount }");
      doSendPrivate(options);
    }

     /**
   * Returns the balance for an account
   *
   * @param options optional parameters: symbol
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get balance objects array.getJSONObject(index)
   */
    public void balance(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addBalanceHandler(msgHandler);
      options.put("action", "privateGetBalance");
      doSendPrivate(options);
    }

      /**
   * Returns the deposit address which can be used to increase the account balance
   *
   * @param symbol symbol specifying the crypto for which the deposit address should be returned
   * @param msgHandler callback
   * @return JSONObject response, get address through response.getJSONObject("response").getString("address")
   */
    public void depositAssets(final String symbol,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addDepositAssetsHandler(msgHandler);
      final JSONObject options = new JSONObject("{ action: privateDepositAssets }");
      options.put("symbol", symbol);
      doSendPrivate(options);
    }

  /**
   * Creates a withdrawal to another address
   *
   * @param symbol symbol specifying the crypto for which the withdrawal should be created
   * @param amount string specifying the amount which should be withdrawn
   * @param address string specifying the address to which the crypto should be sent
   * @param body optional parameters: paymentId, internal, addWithdrawalFee
   * @param msgHandler callback
   * @return JSONObject response, get success confirmation through response.getJSONObject("response").getBoolean("success")
   */
    public void withdrawAssets(final String symbol,final String amount,final String address,final JSONObject body,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addWithdrawAssetsHandler(msgHandler);
      body.put("action", "privateWithdrawAssets");
      body.put("symbol", symbol);
      body.put("amount", amount);
      body.put("address", address);
      doSendPrivate(body);
    }

    /**
   * Returns the entire deposit history for an account
   *
   * @param options optional parameters: symbol, limit, start, end
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get deposit objects array.getJSONObject(index)
   */
    public void depositHistory(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addDepositHistoryHandler(msgHandler);
      options.put("action", "privateGetDepositHistory");
      doSendPrivate(options);
    }

  /**
   * Returns the entire withdrawal history for an account
   *
   * @param options optional parameters: symbol, limit, start, end
   * @param msgHandler callback
   * @return JSONObject response, get array through response.getJSONArray("response") and iterate over the array to get withdrawal objects array.getJSONObject(index)
   */
    public void withdrawalHistory(final JSONObject options,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addWithdrawalHistoryHandler(msgHandler);
      options.put("action", "privateGetWithdrawalHistory");
      doSendPrivate(options);
    }

    /**
   * Pushes an update every time the ticker for a market is updated
   *
   * @param market market for which tickers should be returned
   * @param msgHandler callback, will be overwritten when subscriptionTicker() is called with the same market parameter.
   * @return JSONObject response, get bestBid through response.getString("bestBid") and bestAsk through response.getString("bestAsk")
   */
    public void subscriptionTicker(final String market,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionTickerHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "ticker");
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionTicker = true;
      if(optionsSubscriptionTicker == null) {
        optionsSubscriptionTicker = new JSONObject();
      }
      optionsSubscriptionTicker.put(market, options);
      doSendPublic(options);
    }

    /**
   * Pushes an update every time the 24 hour ticker for a market is updated
   *
   * @param market market for which tickers should be returned
   * @param msgHandler callback, will be overwritten when subscriptionTicker() is called with the same market parameter.
   * @return JSONObject ticker, get individual fields through ticker.getString(<key>)
   */
    public void subscriptionTicker24h(final String market,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionTicker24hHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "ticker24h");
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionTicker24h = true;
      if(optionsSubscriptionTicker24h == null) {
        optionsSubscriptionTicker24h = new JSONObject();
      }
      optionsSubscriptionTicker24h.put(market, options);
      doSendPublic(options);
    }

    /**
   * Pushes an update every time an order is placed, order is canceled or trade is made for an account
   *
   * @param msgHandler callback, will be overwritten on multiple calls to subscriptionAccount
   * @return JSONObject response, get type of event through response.getString("event")
   */
    public void subscriptionAccount(final String market,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionAccountHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "account");
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionAccount = true;
      if(optionsSubscriptionAccount == null) {
        optionsSubscriptionAccount = new JSONObject();
      }
      optionsSubscriptionAccount.put(market, options);
      doSendPrivate(options);
    }

    /**
   * Pushes an update every time an candle is formed for the specified market and interval
   *
   * @param market market for which candles should be pushed.
   * @param interval interval for which the candles should be pushed
   * @param msgHandler callback, will be overwritten on multiple calls to subscriptionCandles() with the same market and interval
   * @return JSONObject response, get candle array (containing a single candle with open, high, low, close and volume) through response.getJSONObject("response").getJSONArray("candle")
   */
    public void subscriptionCandles(final String market,final String interval,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionCandlesHandler(market, interval, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "candles");
      subOptions.put("interval", new String[] {interval});
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionCandles = true;
      final JSONObject intervalIndex = new JSONObject();
      intervalIndex.put(interval, options);
      if(optionsSubscriptionCandles == null) {
        optionsSubscriptionCandles = new JSONObject();
      }
      optionsSubscriptionCandles.put(market, intervalIndex);
      doSendPublic(options);
    }

    /**
   * Pushes an update every time a trade is made within a market.
   *
   * @param market market for which trades should be pushed.
   * @param msgHandler callback, will be overwritten on multiple calls to subscriptionTrades() with the same market
   * @return JSONObject response, get trade object through response.getJSONObject("response")
   */
    public void subscriptionTrades(final String market,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionTradesHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "trades");
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionTrades = true;
      if(optionsSubscriptionTrades == null) {
        optionsSubscriptionTrades = new JSONObject();
      }
      optionsSubscriptionTrades.put(market, options);
      doSendPublic(options);
    }

      /**
     * Pushes an update every time the book for a market is changed.
     *
     * @param market market for which book updates should be pushed.
     * @param msgHandler callback, will be overwritten on multiple calls to subscriptionBook() with the same market
     * @return JSONObject response, the object only contains updates and not the entire book, get the updates through response.getJSONObject("response").getJSONArray("bids"/"asks")
     */
    public void subscriptionBookUpdate(final String market,final WebsocketClientEndpoint.MessageHandler msgHandler) {
      ws.addSubscriptionBookUpdateHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "book");
      subOptions.put("markets", new String[] {market});
      options.put("action", "subscribe");
      options.put("channels", new JSONObject[] {subOptions});
      activatedSubscriptionBookUpdate = true;
      if(optionsSubscriptionBookUpdate == null) {
        optionsSubscriptionBookUpdate = new JSONObject();
      }
      optionsSubscriptionBookUpdate.put(market, options);
      doSendPublic(options);
    }

    /**
     * Pushes the entire book every time it is changed.
     *
     * @param market market for which book updates should be pushed.
     * @param msgHandler callback, will be overwritten on multiple calls to subscriptionBook() with the same market
     * @return Map<String, Object> book, this object has been converted to a Map such that List<List<String>> bids = (List<List<String>>)book.get("bids"); The entire book is contained in every callback
     */
    public void subscriptionBook(final String market,final WebsocketClientEndpoint.BookHandler msgHandler) {
      ws.setKeepBookCopy(true);
      final Map<String, Object> bidsAsks = new HashMap<>();
      bidsAsks.put("bids", new ArrayList<ArrayList<Float>>());
      bidsAsks.put("asks", new ArrayList<ArrayList<Float>>());

      book.put(market, bidsAsks);
      ws.addSubscriptionBookHandler(market, msgHandler);
      final JSONObject options = new JSONObject();
      options.put("action", "getBook");
      options.put("market", market);
      activatedSubscriptionBook = true;
      if(optionsSubscriptionBookFirst == null) {
        optionsSubscriptionBookFirst = new JSONObject();
      }
      optionsSubscriptionBookFirst.put(market, options);
      doSendPublic(options);

      final JSONObject secondOptions = new JSONObject();
      final JSONObject subOptions = new JSONObject();
      subOptions.put("name", "book");
      subOptions.put("markets", new String[] {market});
      secondOptions.put("action", "subscribe");
      secondOptions.put("channels", new JSONObject[] {subOptions});
      if(optionsSubscriptionBookSecond == null) {
        optionsSubscriptionBookSecond = new JSONObject();
      }
      optionsSubscriptionBookSecond.put(market, secondOptions);
      doSendPublic(secondOptions);
    }
  }
  
  public boolean isDebugging() {
   return debugging;
  }

  public KeepAliveThread getKeepAliveThread() {
   return keepAliveThread;
  }

  public boolean isActivatedSubscriptionTicker() {
   return activatedSubscriptionTicker;
  }

  public boolean isActivatedSubscriptionTicker24h() {
   return activatedSubscriptionTicker24h;
  }

  public boolean isActivatedSubscriptionAccount() {
   return activatedSubscriptionAccount;
  }

  public boolean isActivatedSubscriptionCandles() {
   return activatedSubscriptionCandles;
  }

  public boolean isActivatedSubscriptionTrades() {
   return activatedSubscriptionTrades;
  }

  public boolean isActivatedSubscriptionBookUpdate() {
   return activatedSubscriptionBookUpdate;
  }

  public boolean isActivatedSubscriptionBook() {
   return activatedSubscriptionBook;
  }

  public boolean isAuthenticated() {
   return authenticated;
  }

  public void setAuthenticated(boolean authenticated) {
   this.authenticated = authenticated;
  }

  public int getWindow() {
   return window;
  }

  public void setWindow(int window) {
   this.window = window;
  }

  public JSONObject getOptionsSubscriptionTicker() {
   return optionsSubscriptionTicker;
  }

  public JSONObject getOptionsSubscriptionTicker24h() {
   return optionsSubscriptionTicker24h;
  }

  public JSONObject getOptionsSubscriptionAccount() {
   return optionsSubscriptionAccount;
  }

  public JSONObject getOptionsSubscriptionCandles() {
   return optionsSubscriptionCandles;
  }

  public JSONObject getOptionsSubscriptionTrades() {
   return optionsSubscriptionTrades;
  }

  public JSONObject getOptionsSubscriptionBookUpdate() {
   return optionsSubscriptionBookUpdate;
  }

  public JSONObject getOptionsSubscriptionBookFirst() {
   return optionsSubscriptionBookFirst;
  }

  public JSONObject getOptionsSubscriptionBookSecond() {
   return optionsSubscriptionBookSecond;
  }

  public WebsocketClientEndpoint getWs() {
   return ws;
  }

  public void setWs(WebsocketClientEndpoint ws) {
   this.ws = ws;
  }

  public String getWsUrl() {
   return wsUrl;
  }

  public Websocket getWebsocketObject() {
   return websocketObject;
  }

  public Map<String, Object> getBook() {
   return book;
  }  
}
