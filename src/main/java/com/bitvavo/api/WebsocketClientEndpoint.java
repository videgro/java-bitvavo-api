package com.bitvavo.api;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@ClientEndpoint
public class WebsocketClientEndpoint {
    private static final Logger LOGGER = LogManager.getLogger();

    private int reconnectTimer;
    private Bitvavo bitvavo;
    private Session userSession = null;
    private boolean restartWebsocket = true;
    private boolean keepBookCopy;
    private MessageHandler messageHandler;
    private MessageHandler timeHandler;
    private MessageHandler marketsHandler;
    private MessageHandler assetsHandler;
    private MessageHandler bookHandler;
    private MessageHandler tradesHandler;
    private MessageHandler candlesHandler;
    private MessageHandler ticker24hHandler;
    private MessageHandler tickerPriceHandler;
    private MessageHandler tickerBookHandler;
    private MessageHandler createOrderHandler;
    private MessageHandler getOrderHandler;
    private MessageHandler updateOrderHandler;
    private MessageHandler cancelOrderHandler;
    private MessageHandler cancelOrdersHandler;
    private MessageHandler getOrdersHandler;
    private MessageHandler getOrdersOpenHandler;
    private MessageHandler getTradesHandler;
    private MessageHandler getAccountHandler;
    private MessageHandler balanceHandler;
    private MessageHandler depositAssetsHandler;
    private MessageHandler withdrawAssetsHandler;
    private MessageHandler depositHistoryHandler;
    private MessageHandler withdrawalHistoryHandler;
    private MessageHandler authenticateHandler;
    private MessageHandler errorHandler;
    private Map<String, HashMap<String, MessageHandler>> subscriptionCandlesHandlerMap;
    private Map<String, MessageHandler> subscriptionAccountHandlerMap;
    private Map<String, MessageHandler> subscriptionTickerHandlerMap;
    private Map<String, MessageHandler> subscriptionTicker24hHandlerMap;
    private Map<String, MessageHandler> subscriptionTradesHandlerMap;
    private Map<String, MessageHandler> subscriptionBookUpdateHandlerMap;
    private Map<String, BookHandler> subscriptionBookHandlerMap;

    public WebsocketClientEndpoint(final URI endpointURI,final Bitvavo bitv) {
        try {
            bitvavo = bitv;
            final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, endpointURI);
        } catch (final IOException | DeploymentException e) {
            LOGGER.error("Caught exception in instantiating websocket.",e);
            reconnectTimer = 100;
            retryConnecting(endpointURI);
        }
    }

    public void retryConnecting(final URI endpointURI) {
        debug("Trying to reconnect.");
        try {
        	final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, endpointURI);
        } catch (javax.websocket.DeploymentException e) {
            try {
                TimeUnit.MILLISECONDS.sleep(this.reconnectTimer);
                this.reconnectTimer = this.reconnectTimer * 2;
                debug("We waited for " + this.reconnectTimer / 1000.0 + " seconds");
                retryConnecting(endpointURI);
            } catch(InterruptedException e1) {
            	LOGGER.error(e1);                
            }
        } catch (final Exception error) {
            LOGGER.error("unexpected exception caught",error);
            throw new RuntimeException(error);
        }
    }

    public void closeSocket() {
        try {
            if (userSession != null) {
                restartWebsocket = false;
                userSession.close();
                LOGGER.trace(bitvavo.getKeepAliveThread());                
                bitvavo.getKeepAliveThread().interrupt();
            }
        } catch (IOException e) {
        	LOGGER.error(e);            
        }
    }

    @OnOpen
    public void onOpen(final Session userSession) {
        debug("opening websocket");
        this.userSession = userSession;
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (final InterruptedException e) {
            LOGGER.error(e);
        }
        if(!bitvavo.getApiKey().isEmpty()) {
            final long timestamp = System.currentTimeMillis();
            final JSONObject authenticate = new JSONObject();
            authenticate.put("action", "authenticate");
            authenticate.put("key", bitvavo.getApiKey());
            authenticate.put("signature", bitvavo.createSignature(timestamp, "GET", "/websocket", new JSONObject()));
            authenticate.put("timestamp", timestamp);
            authenticate.put("window", Integer.toString(bitvavo.getWindow()));
            sendMessage(authenticate.toString());
        }
        if (bitvavo.isActivatedSubscriptionTicker()) {
            final Iterator<String> markets = bitvavo.getOptionsSubscriptionTicker().keys();
            while (markets.hasNext()) {
                sendMessage(bitvavo.getOptionsSubscriptionTicker().get(markets.next()).toString());
            }
        }
        if (bitvavo.isActivatedSubscriptionTicker24h()) {
            final Iterator<String> markets = bitvavo.getOptionsSubscriptionTicker24h().keys();
            while (markets.hasNext()) {                
                sendMessage(bitvavo.getOptionsSubscriptionTicker24h().get(markets.next()).toString());
            }
        }
        // Account uses a threaded function, since we need a response on authenticate before we can send.
        if (bitvavo.isActivatedSubscriptionAccount()) {
            final WebsocketSendThread websocketSendThread = new WebsocketSendThread(bitvavo.getOptionsSubscriptionAccount(), bitvavo, this);
            websocketSendThread.start();
        }
        if (bitvavo.isActivatedSubscriptionCandles()) {
            final Iterator<String> markets = bitvavo.getOptionsSubscriptionCandles().keys();
            while (markets.hasNext()) {
                final String market = markets.next();
                final JSONObject intervalObject = bitvavo.getOptionsSubscriptionCandles().getJSONObject(market);
                final Iterator<String> intervals = intervalObject.keys();
                while (intervals.hasNext()) {                    
                    sendMessage(bitvavo.getOptionsSubscriptionCandles().getJSONObject(market).get(intervals.next()).toString());
                }
            }
        }
        if (bitvavo.isActivatedSubscriptionTrades()) {
            final Iterator<String> markets = bitvavo.getOptionsSubscriptionTrades().keys();
            while (markets.hasNext()) {            	
                sendMessage(bitvavo.getOptionsSubscriptionTrades().get(markets.next()).toString());
            }
        }
        if (bitvavo.isActivatedSubscriptionBookUpdate()) {
        	final Iterator<String> markets = bitvavo.getOptionsSubscriptionBookUpdate().keys();
            while (markets.hasNext()) {
                sendMessage(bitvavo.getOptionsSubscriptionBookUpdate().get(markets.next()).toString());
            }
        }
        if (bitvavo.isActivatedSubscriptionBook()) {
            final Iterator<String> markets = bitvavo.getOptionsSubscriptionBookFirst().keys();
            while (markets.hasNext()) {
            	final String market = markets.next();
                sendMessage(bitvavo.getOptionsSubscriptionBookFirst().get(market).toString());
                sendMessage(bitvavo.getOptionsSubscriptionBookSecond().get(market).toString());
            }
        }
        debug("We completed onOpen");
    }

    @OnError
    public void onError(final Session userSession,final Throwable error) {
        LOGGER.error("We encountered an error",error);
    }

    private void copyHandlers(final WebsocketClientEndpoint oldCE,final WebsocketClientEndpoint newCE) {
        if (oldCE.keepBookCopy) {
            newCE.keepBookCopy = true;
        }
        newCE.copySubscriptionTickerHandler(oldCE.subscriptionTickerHandlerMap);
        newCE.copySubscriptionTicker24hHandler(oldCE.subscriptionTicker24hHandlerMap);
        newCE.copySubscriptionAccountHandler(oldCE.subscriptionAccountHandlerMap);
        newCE.copySubscriptionBookUpdateHandler(oldCE.subscriptionBookUpdateHandlerMap);
        newCE.copySubscriptionCandlesHandler(oldCE.subscriptionCandlesHandlerMap);
        newCE.copySubscriptionTradesHandler(oldCE.subscriptionTradesHandlerMap);
        newCE.copySubscriptionBookHandler(oldCE.subscriptionBookHandlerMap);
    }

    @OnClose
    public void onClose(final Session userSession,final CloseReason reason) {
        debug("closing websocket " + reason);
        this.userSession = null;
        if(bitvavo.getRemainingLimit() > 0 && restartWebsocket) {
            try {
                bitvavo.setAuthenticated(false);
                final WebsocketClientEndpoint clientEndPoint = new WebsocketClientEndpoint(new URI(bitvavo.getWsUrl()), bitvavo);
                copyHandlers(bitvavo.getWs(), clientEndPoint);
                clientEndPoint.addAuthenticateHandler(new WebsocketClientEndpoint.MessageHandler() {
                    public void handleMessage(JSONObject response) {
                        if(response.has("authenticated")) {
                            bitvavo.setAuthenticated(true);
                            debug("We registered authenticated as true again");
                        }
                    }
                });
                clientEndPoint.addMessageHandler(new WebsocketClientEndpoint.MessageHandler() {
                    public void handleMessage(JSONObject response) {
                        LOGGER.error("Unexpected message: {}",response);
                    }
                });
                bitvavo.setWs(clientEndPoint);
            } catch (final Exception ex) {
            	LOGGER.error("We caught exception in reconnecting!",ex);
            }
        } else {
            if (restartWebsocket) {
            	debug("The websocket has been closed because your rate limit was reached, please wait till the ban is lifted and try again.");
            } else {
            	debug("The websocket has been closed by the user.");
            }
        }
    }

    public static Map<String, Object> jsonToMap(final JSONObject json) throws JSONException {
        Map<String, Object> returnMap = new HashMap<>();

        if(json != JSONObject.NULL) {
            returnMap = toMap(json);
        }
        return returnMap;
    }

    public static Map<String, Object> toMap(final JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<>();

        final Iterator<String> keysItr = object.keys();
        while (keysItr.hasNext()) {
            final String key = keysItr.next();
            Object value = object.get(key);

            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    public static List<Object> toList(final JSONArray array) throws JSONException {
        final List<Object> list = new ArrayList<>();
        for(int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    private List<List<String>> sortAndInsert(final List<List<String>> update,final List<List<String>> book,final boolean asksCompare) {
        for(int i = 0; i < update.size(); i++) {
            boolean updateSet = false;
            final List<String> updateEntry = update.get(i);
            for (int j = 0; j < book.size(); j++) {
                final List<String> bookItem = book.get(j);
                if (asksCompare) {
                    if (Float.parseFloat(updateEntry.get(0)) < Float.parseFloat(bookItem.get(0))) {
                        book.add(j, updateEntry);
                        updateSet = true;
                        break;
                    }
                } else {
                    if (Float.parseFloat(updateEntry.get(0)) > Float.parseFloat(bookItem.get(0))) {
                        book.add(j, updateEntry);
                        updateSet = true;
                        break;
                    }
                }
                if (Float.parseFloat(bookItem.get(0)) == Float.parseFloat(updateEntry.get(0))) {
                    if(Float.parseFloat(updateEntry.get(1)) > 0.0) {
                        book.set(j, updateEntry);
                        updateSet = true;
                        break;
                    } else {
                        book.remove(j);
                        updateSet = true;
                        break;
                    }
                }
            }
            if (!updateSet) {
                book.add(updateEntry);
            }
        }
        return book;
    }

    private void debug(final String message) {
    	if(bitvavo.isDebugging() && LOGGER.isDebugEnabled())  {
          LOGGER.debug(message);
        }
    }
    
    @SuppressWarnings("unchecked")
    @OnMessage
    public void onMessage(final String message) {
        String market;
        final JSONObject response = new JSONObject(message);
        debug("FULLRESPONSE: " + response);
        if (response.has("error")) {
            bitvavo.errorRateLimit(response);
            if(errorHandler != null) {
                errorHandler.handleMessage(response);
                return;
            }
        }
        if (response.has("event")) {
            if(response.getString("event").equals("subscribed")) {
                final JSONObject channel = response.getJSONObject("subscriptions");
                String subscribedString = "We are now subscribed to the following channels: ";
                final Iterator<String> keys = channel.keys();
                while(keys.hasNext()) {
                    String key = keys.next();
                    subscribedString = subscribedString + key + ", ";
                }
                debug(subscribedString.substring(0, subscribedString.length() - 2));
            } else if(response.getString("event").equals("authenticate")) {
                if (authenticateHandler != null) {
                    authenticateHandler.handleMessage(response);
                }
            } else if(response.getString("event").equals("trade")) {
                market = response.getString("market");
                if (subscriptionTradesHandlerMap != null) {
                    if (subscriptionTradesHandlerMap.get(market) != null) {
                        subscriptionTradesHandlerMap.get(market).handleMessage(response);
                    }
                }
            } else if (response.getString("event").equals("fill")) {
                market = response.getString("market");
                if (subscriptionAccountHandlerMap != null) {
                    if (subscriptionAccountHandlerMap.get(market) != null) {
                        subscriptionAccountHandlerMap.get(market).handleMessage(response);
                    }
                }
            } else if (response.getString("event").equals("order")) {
                market = response.getString("market");
                if (subscriptionAccountHandlerMap != null) {
                    if (subscriptionAccountHandlerMap.get(market) != null) {
                        subscriptionAccountHandlerMap.get(market).handleMessage(response);
                    }
                }
            } else if (response.getString("event").equals("ticker")) {
                market = response.getString("market");
                if (subscriptionTickerHandlerMap != null) {
                    if (subscriptionTickerHandlerMap.get(market) != null) {
                        subscriptionTickerHandlerMap.get(market).handleMessage(response);
                    }
                }
            } else if (response.getString("event").equals("ticker24h")) {
                final JSONArray data = response.getJSONArray("data");
                if (subscriptionTicker24hHandlerMap != null) {
                    for (int i = 0; i < data.length(); i++) {
                        final JSONObject ticker = data.getJSONObject(i);
                        market = ticker.getString("market");
                        if (subscriptionTicker24hHandlerMap.get(market) != null) {
                            subscriptionTicker24hHandlerMap.get(market).handleMessage(ticker);
                        }
                    }
                }
            } else if (response.getString("event").equals("book")) {
                market = response.getString("market");
                if (subscriptionBookUpdateHandlerMap != null) {
                    if(subscriptionBookUpdateHandlerMap.get(market) != null) {
                        subscriptionBookUpdateHandlerMap.get(market).handleMessage(response);
                    }
                }
                if (keepBookCopy) {
                    if(subscriptionBookHandlerMap != null) {
                        if(subscriptionBookHandlerMap.get(market) != null) {
                            final Map<String, Object> responseMap = jsonToMap(response);
                            market = (String)responseMap.get("market");
                            boolean restartLocalBook = false;

                            final Map<String, Object> bidsAsks = (Map<String, Object>)bitvavo.getBook().get(market);
                            List<List<String>> bids = (List<List<String>>)bidsAsks.get("bids");
                            List<List<String>> asks = (List<List<String>>)bidsAsks.get("asks");
                            
                            final List<List<String>> bidsInput = (List<List<String>>)responseMap.get("bids");
                            final List<List<String>> asksInput = (List<List<String>>)responseMap.get("asks");

                            if( (int)responseMap.get("nonce") != Integer.parseInt((String)bidsAsks.get("nonce")) + 1) {
                                restartLocalBook = true;
                                bitvavo.getWebsocketObject().subscriptionBook(market, this.subscriptionBookHandlerMap.get(market));
                            }
                            if(!restartLocalBook) {
                                bids = sortAndInsert(bidsInput, bids, false);
                                asks = sortAndInsert(asksInput, asks, true);
                                bidsAsks.put("bids", bids);
                                bidsAsks.put("asks", asks);
                                bidsAsks.put("nonce", Integer.toString((int)responseMap.get("nonce")));
                                bitvavo.getBook().put(market, bidsAsks);
                            
                                subscriptionBookHandlerMap.get(market).handleBook((Map<String, Object>)bitvavo.getBook().get(market));
                            }
                        }
                    }
                }
            } else if (response.getString("event").equals("candle")) {
                market = response.getString("market");
                final String interval = response.getString("interval");
                if (subscriptionCandlesHandlerMap.get(market) != null) {
                    if(subscriptionCandlesHandlerMap.get(market).get(interval) != null) {
                        subscriptionCandlesHandlerMap.get(market).get(interval).handleMessage(response);
                    }
                }
            }
        } else if(response.has("action")) {
            if (response.getString("action").equals("getTime")) {
                if (timeHandler != null) {
                    timeHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getMarkets")) {
                if (marketsHandler != null) {
                    marketsHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getAssets")) {
                if (assetsHandler != null) {
                    assetsHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getBook")) {
                if (bookHandler != null) {
                    bookHandler.handleMessage(response);
                }
                if (keepBookCopy) {
                    if(this.subscriptionBookHandlerMap != null) {
                        market = response.getJSONObject("response").getString("market");
                        if(subscriptionBookHandlerMap.get(market) != null) {
                            final Map<String, Object> bidsAsks = (Map<String, Object>)bitvavo.getBook().get(market);
                            //final List<List<String>> bids = (List<List<String>>)bidsAsks.get("bids");
                            //final List<List<String>> asks = (List<List<String>>)bidsAsks.get("asks");

                            final Map<String, Object> bookentry = jsonToMap(response.getJSONObject("response"));

                            final List<List<String>> bidsInput = (List<List<String>>)bookentry.get("bids");
                            final List<List<String>> asksInput = (List<List<String>>)bookentry.get("asks");

                            bidsAsks.put("bids", bidsInput);
                            bidsAsks.put("asks", asksInput);
                            bidsAsks.put("nonce", Integer.toString(response.getJSONObject("response").getInt("nonce")));
                            bitvavo.getBook().put(market, bidsAsks);
                        
                            subscriptionBookHandlerMap.get(market).handleBook((Map<String, Object>)bitvavo.getBook().get(market));
                        }
                    }
                }
            } else if(response.getString("action").equals("getTrades")) {
                if (tradesHandler != null) {
                    tradesHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getCandles")) {
                if (candlesHandler != null) {
                    candlesHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getTicker24h")) {
                if (ticker24hHandler != null) {
                    ticker24hHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getTickerPrice")) {
                if (tickerPriceHandler != null) {
                    tickerPriceHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("getTickerBook")) {
                if (tickerBookHandler != null) {
                    tickerBookHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateCreateOrder")) {
                if (createOrderHandler != null) {
                    createOrderHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetOrder")) {
                if (getOrderHandler != null) {
                    getOrderHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateUpdateOrder")) {
                if (updateOrderHandler != null) {
                    updateOrderHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateCancelOrder")) {
                if (cancelOrderHandler != null) {
                    cancelOrderHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetOrders")) {
                if (getOrdersHandler != null) {
                    getOrdersHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateCancelOrders")) {
                if (cancelOrdersHandler != null) {
                    cancelOrdersHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetOrdersOpen")) {
                if (getOrdersOpenHandler != null) {
                    getOrdersOpenHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetTrades")) {
                if (getTradesHandler != null) {
                    getTradesHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetAccount")) {
                if (getAccountHandler != null) {
                    getAccountHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetBalance")) {
                if(balanceHandler != null) {
                    balanceHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateDepositAssets")) {
                if(depositAssetsHandler != null) {
                    depositAssetsHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateWithdrawAssets")) {
                if(withdrawAssetsHandler != null) {
                    withdrawAssetsHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetDepositHistory")) {
                if(depositHistoryHandler != null) {
                    depositHistoryHandler.handleMessage(response);
                }
            } else if(response.getString("action").equals("privateGetWithdrawalHistory")) {
                if(withdrawalHistoryHandler != null) {
                    withdrawalHistoryHandler.handleMessage(response);
                }
            }
        } else if (messageHandler != null) {
            messageHandler.handleMessage(response);
        }
    }

    public void addMessageHandler(final MessageHandler msgHandler) {
        this.messageHandler = msgHandler;
    }

    public void addErrorHandler(final MessageHandler msgHandler) {
        this.errorHandler = msgHandler;
    }

    public void addTimeHandler(final MessageHandler msgHandler) {
        this.timeHandler = msgHandler;
    }

    public void addMarketsHandler(final MessageHandler msgHandler) {
        this.marketsHandler = msgHandler;
    }

    public void addAssetsHandler(final MessageHandler msgHandler) {
        this.assetsHandler = msgHandler;
    }

    public void addBookHandler(final MessageHandler msgHandler) {
        this.bookHandler = msgHandler;
    }

    public void addTradesHandler(final MessageHandler msgHandler) {
        this.tradesHandler = msgHandler;
    }

    public void addCandlesHandler(final MessageHandler msgHandler) {
        this.candlesHandler = msgHandler;
    }

    public void addTicker24hHandler(final MessageHandler msgHandler) {
        this.ticker24hHandler = msgHandler;
    }

    public void addTickerPriceHandler(final MessageHandler msgHandler) {
        this.tickerPriceHandler = msgHandler;
    }

    public void addTickerBookHandler(final MessageHandler msgHandler) {
        this.tickerBookHandler = msgHandler;
    }

    public void addPlaceOrderHandler(final MessageHandler msgHandler) {
        this.createOrderHandler = msgHandler;
    }

    public void addGetOrderHandler(final MessageHandler msgHandler) {
        this.getOrderHandler = msgHandler;
    }

    public void addUpdateOrderHandler(final MessageHandler msgHandler) {
        this.updateOrderHandler = msgHandler;
    }

    public void addCancelOrderHandler(final MessageHandler msgHandler) {
        this.cancelOrderHandler = msgHandler;
    }

    public void addGetOrdersHandler(final MessageHandler msgHandler) {
        this.getOrdersHandler = msgHandler;
    }

    public void addCancelOrdersHandler(final MessageHandler msgHandler) {
        this.cancelOrdersHandler = msgHandler;
    }

    public void addGetOrdersOpenHandler(final MessageHandler msgHandler) {
        this.getOrdersOpenHandler = msgHandler;
    }

    public void addGetTradesHandler(final MessageHandler msgHandler) {
        this.getTradesHandler = msgHandler;
    }

    public void addAccountHandler(final MessageHandler msgHandler) {
        this.getAccountHandler = msgHandler;
    }

    public void addBalanceHandler(final MessageHandler msgHandler) {
        this.balanceHandler = msgHandler;
    }

    public void addDepositAssetsHandler(final MessageHandler msgHandler) {
        this.depositAssetsHandler = msgHandler;
    }

    public void addWithdrawAssetsHandler(final MessageHandler msgHandler) {
        this.withdrawAssetsHandler = msgHandler;
    }

    public void addDepositHistoryHandler(final MessageHandler msgHandler) {
        this.depositHistoryHandler = msgHandler;
    }

    public void addWithdrawalHistoryHandler(final MessageHandler msgHandler) {
        this.withdrawalHistoryHandler = msgHandler;
    }

    public void addSubscriptionTickerHandler(final String market,final MessageHandler msgHandler) {
        if(subscriptionTickerHandlerMap == null) {
            subscriptionTickerHandlerMap = new HashMap<>();
        }
        subscriptionTickerHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionTickerHandler(final Map<String, MessageHandler> map) {
        this.subscriptionTickerHandlerMap = map;
    }

    public void addSubscriptionTicker24hHandler(final String market,final MessageHandler msgHandler) {
        if(subscriptionTicker24hHandlerMap == null) {
            subscriptionTicker24hHandlerMap = new HashMap<>();
        }
        subscriptionTicker24hHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionTicker24hHandler(final Map<String, MessageHandler> map) {
        this.subscriptionTicker24hHandlerMap = map;
    }

    public void addSubscriptionAccountHandler(final String market,final MessageHandler msgHandler) {
        if(subscriptionAccountHandlerMap == null) {
            subscriptionAccountHandlerMap = new HashMap<>();
        }
        subscriptionAccountHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionAccountHandler(final Map<String, MessageHandler> map) {
        this.subscriptionAccountHandlerMap = map;
    }

    public void addSubscriptionCandlesHandler(final String market,final String interval,final MessageHandler msgHandler) {
        if (subscriptionCandlesHandlerMap == null) {
            subscriptionCandlesHandlerMap = new HashMap<>();
        }
        if (subscriptionCandlesHandlerMap.get(market) != null) {
            subscriptionCandlesHandlerMap.get(market).put(interval, msgHandler);
        } else {
            subscriptionCandlesHandlerMap.put(market, new HashMap<>());
            subscriptionCandlesHandlerMap.get(market).put(interval, msgHandler);
        }
    }

    public void copySubscriptionCandlesHandler(final Map<String, HashMap<String, MessageHandler>> map) {
        this.subscriptionCandlesHandlerMap = map;
    }

    public void addSubscriptionTradesHandler(final String market,final MessageHandler msgHandler) {
        if (subscriptionTradesHandlerMap == null) {
            subscriptionTradesHandlerMap = new HashMap<>();
        }
        subscriptionTradesHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionTradesHandler(final Map<String, MessageHandler> map) {
        this.subscriptionTradesHandlerMap = map;
    }

    public void addSubscriptionBookUpdateHandler(final String market,final MessageHandler msgHandler) {
        if (subscriptionBookUpdateHandlerMap == null) {
            subscriptionBookUpdateHandlerMap = new HashMap<>();
        }
        subscriptionBookUpdateHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionBookUpdateHandler(final Map<String, MessageHandler> map) {
        this.subscriptionBookUpdateHandlerMap = map;
    }

    public void addAuthenticateHandler(final MessageHandler msgHandler) {
        this.authenticateHandler = msgHandler;
    }

    public void addSubscriptionBookHandler(final String market,final BookHandler msgHandler) {
        if (subscriptionBookHandlerMap == null) {
            subscriptionBookHandlerMap = new HashMap<>();
        }
        subscriptionBookHandlerMap.put(market, msgHandler);
    }

    public void copySubscriptionBookHandler(final Map<String, BookHandler> map) {
        this.subscriptionBookHandlerMap = map;
    }

    public void sendMessage(final String message) {
        debug("Sending message " + message);
        userSession.getAsyncRemote().sendText(message);
    }

    public static interface MessageHandler {
        public void handleMessage(JSONObject response);
    }

    public static interface BookHandler {
        public void handleBook(Map<String, Object> book);
    }

    public void setKeepBookCopy(final boolean keepBookCopy) {
        this.keepBookCopy = keepBookCopy;
    }
}