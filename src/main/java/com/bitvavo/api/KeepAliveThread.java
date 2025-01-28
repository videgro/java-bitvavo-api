package com.bitvavo.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KeepAliveThread extends Thread {
  private static final Logger LOGGER = LogManager.getLogger();

  @Override
  public void run(){
    boolean keepRunning = true;
    while(keepRunning) {
      try {
        Thread.sleep(200);
      } catch(InterruptedException e) {
        keepRunning = false;
        LOGGER.error("KeepAliveThread got interrupted, terminating thread.",e);
      }
    }
  }
}