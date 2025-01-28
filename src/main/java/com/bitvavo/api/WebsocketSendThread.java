package com.bitvavo.api;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

public class WebsocketSendThread extends Thread {
  private static final Logger LOGGER = LogManager.getLogger();

  private JSONObject options;
  private Bitvavo bitvavo;
  private WebsocketClientEndpoint ws;

  public WebsocketSendThread(final JSONObject options,final Bitvavo bitv,final WebsocketClientEndpoint ws) {
    this.options = options;
    this.bitvavo = bitv;
    this.ws = ws;
  }

  public void sendPrivate(final JSONObject options) {
    if (bitvavo.isAuthenticated()) {
      final Iterator<String> markets = options.keys();
      while(markets.hasNext()) {
        final String market = markets.next();
        this.ws.sendMessage(options.get(market).toString());
      }
    } else {
      try {
        TimeUnit.MILLISECONDS.sleep(500);
        sendPrivate(options);
      } catch (InterruptedException e) {
        LOGGER.error("something went wrong while sending private request.",e);
      }
    }
  }

  @Override
  public void run(){
    sendPrivate(this.options);
  }
}