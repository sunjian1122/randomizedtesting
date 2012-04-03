package com.carrotsearch.ant.tasks.junit4;

import java.io.*;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.Charset;
import java.util.List;

import org.apache.tools.ant.taskdefs.ExecuteStreamHandler;
import org.apache.tools.ant.taskdefs.StreamPumper;

import com.carrotsearch.ant.tasks.junit4.events.*;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;

/**
 * Establish event passing with a subprocess and pump events to the bus.
 */
public class LocalSlaveStreamHandler implements ExecuteStreamHandler {
  private final EventBus eventBus;
  private final ClassLoader refLoader;
  private BootstrapEvent bootstrapPacket;

  private InputStream stdout;
  private InputStream stderr;
  private OutputStreamWriter stdin;
  private final PrintStream warnStream;

  private ByteArrayOutputStream stderrBuffered = new ByteArrayOutputStream();
  private List<Thread> pumpers = Lists.newArrayList();

  public LocalSlaveStreamHandler(EventBus eventBus, ClassLoader classLoader, PrintStream warnStream) {
    this.eventBus = eventBus;
    this.warnStream = warnStream;
    this.refLoader = classLoader;
  }

  @Override
  public void setProcessErrorStream(InputStream is) throws IOException {
    this.stderr = is;
  }
  
  @Override
  public void setProcessOutputStream(InputStream is) throws IOException {
    this.stdout = is;
  }

  @Override
  public void setProcessInputStream(OutputStream os) throws IOException {
    this.stdin = new OutputStreamWriter(
        os, Charset.defaultCharset());
  }
  
  @Override
  public void start() throws IOException {
    // Establish event stream first.
    Deserializer deserializer = null;

    // Receive bootstrap event on stdout.
    try {
      deserializer = new Deserializer(stdout, refLoader);
      BootstrapEvent bootstrap = (BootstrapEvent) deserializer.deserialize();
      this.bootstrapPacket = bootstrap;

      switch (bootstrap.getEventChannel()) {
        case STDERR:
          // Swap stderr/stdout.
          InputStream tmp = stdout;
          stdout = stderr;
          stderr = tmp;
          break;
        case STDOUT:
          // Don't do anything.
          break;
      }
      eventBus.post(bootstrap);

      pumpers.add(new Thread(new StreamPumper(stderr, stderrBuffered), "pumper-stderr"));
      pumpers.add(new Thread(new Runnable() {
        public void run() {
          pumpEvents();
        }
      }, "pumper-events"));
    } catch (IOException e) {
      warnStream.println("Couldn't establish event communication with the slave: " + e.toString());
      if (!(e instanceof EOFException)) {
        e.printStackTrace(warnStream);
      }
      pumpers.add(new Thread(new StreamPumper(stderr, stderrBuffered), "pumper-stderr"));
    }
    
    // Start all pumper threads.
    UncaughtExceptionHandler handler = new UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        warnStream.println("Unhandled exception in thread: " + t);
        e.printStackTrace(warnStream);
      }
    };
    for (Thread t : pumpers) {
      t.setUncaughtExceptionHandler(handler);
      t.setDaemon(true);
      t.start();
    }
  }

  /**
   * Size of the error stream.
   */
  public boolean isErrorStreamNonEmpty() {
    return stderrBuffered.size() > 0;
  }

  /**
   * "error" stream from the forked process.
   */
  public String getErrorStreamAsString() {
    try {
      if (bootstrapPacket != null) {
        return new String(stderrBuffered.toByteArray(), bootstrapPacket.getDefaultCharsetName());
      }
    } catch (UnsupportedEncodingException e) {
      // Ignore.
    }
    return new String(stderrBuffered.toByteArray(), Charsets.US_ASCII);
  }

  /**
   * Pump events from event stream.
   */
  void pumpEvents() {
    try {
      Deserializer deserializer = new Deserializer(stdout, refLoader);
      IEvent event = null;
      while ((event = deserializer.deserialize()) != null) {
        try {
          if (event.getType() == EventType.IDLE) {
            eventBus.post(new SlaveIdle(stdin));
          } else {
            eventBus.post(event);
          }
        } catch (Throwable t) {
          warnStream.println("Event bus dispatch error: " + t.toString());
          t.printStackTrace(warnStream);
        }
      }
    } catch (EOFException e) {
      // This is a bit unexpected, but don't dump stack trace.
      warnStream.println("Event stream error EOF?");
    } catch (IOException e) {
      warnStream.println("Event stream error: " + e.toString());
      e.printStackTrace(warnStream);
    }
  }
  
  @Override
  public void stop() {
    try {
      for (Thread t : pumpers) {
        t.join();
      }
    } catch (InterruptedException e) {
      // Don't wait.
    }
  }
}