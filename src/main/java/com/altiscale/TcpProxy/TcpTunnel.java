/**
 * Copyright Altiscale 2014
 * Author: Zoran Dimitrijevic <zoran@altiscale.com>
 *
 * TcpTunnel is a class to handle data transfer between incomming-outgoing socket pairs (tunnels)
 * and a threadpool that serves all these existing tunnels.
 *
 * Goal of TcpTunnel is to simply tunnel all data between incoming sockets and their destination
 * sockets, trying to load-balance so that each destination connection transfers data with equal
 * rate.
 *
 * Destination ports are usually ssh-tunnels to a same service.
 *
 **/

package com.altiscale.TcpProxy;

import org.apache.log4j.Logger;

import java.io.*;
import java.lang.Thread;
import java.net.*;
import java.util.ArrayList;

public class TcpTunnel {
  // log4j logger.
  private static Logger LOG = Logger.getLogger("TcpProxy");

  // Proxy who created us.
  TcpProxyServer proxy;

  // Socket from our client to us.
  private Socket clientSocket;

  // Socket from us to our server.
  private Socket serverSocket;

  // Client-to-Server one-directional tunnel.
  OneDirectionTunnel clientServer;

  // Server-to-Client one-directional tunnel.
  OneDirectionTunnel serverClient;

  // We are just a proxy. We create two pipes, proxy all data and whoever closes the
  // connection first our job is to simply close the other end as well.
  protected class OneDirectionTunnel implements Runnable {
    private String threadName;
    private Thread thread;

    private Socket sourceSocket;
    private Socket destinationSocket;

    public OneDirectionTunnel(Socket source, Socket destination, String name) {
      threadName = name;
      thread = null;
      sourceSocket = source;
      destinationSocket = destination;
    }

    public void run() {
      DataInputStream input = null;
      DataOutputStream output = null;
      try {
        input = new DataInputStream(sourceSocket.getInputStream());
        output = new DataOutputStream(destinationSocket.getOutputStream());
      } catch (IOException ioe) {
        LOG.error("Could not open input or output stream.");
        return;
      }
      int cnt = 0;
      byte[] buffer = new byte[1024 * 8];  // 8KB buffer.
      try {
        do {
          // Read some data.
          cnt = input.read(buffer);

          if (cnt > 0) {
            output.write(buffer, 0, cnt);

            // We don't want to buffer too much in the proxy. So, flush the output.
            // TODO(zoran): this might cause some performance issues. Experiment with
            // buffer size and maybe flush less often.
            output.flush();
          }
        } while (cnt >= 0);
      } catch (IOException ioe) {
        LOG.info("Closing socket after IO exception while reading: " + ioe.getMessage());
      }
      // Either the input stream is closed or we got an exception. Either way, close the
      // sockets since we're done with this tunnel.
      try {
        if (!sourceSocket.isClosed()) {
          sourceSocket.close();
        }
        if (!destinationSocket.isClosed()) {
          destinationSocket.close();
        }
      } catch (IOException ioe) {
        LOG.error("IO exception while closing sockets in thread [" + threadName +
            "]: " + ioe.getMessage());
      }
      LOG.info("Exiting thread [" + threadName + "]");
    }

    public Thread start() {
      assert null == thread;  // we should never call this method twice.
      LOG.info("Starting thread [" + threadName + "]");
      thread = new Thread(this, threadName);
      thread.start();
      return thread;
    }
  }

  public TcpTunnel(TcpProxyServer proxy, Socket client, Socket server) {
    clientSocket = client;
    serverSocket = server;

    // Create two one-directional tunnels to connect both pipes.
    clientServer = new OneDirectionTunnel(clientSocket, serverSocket, "clientServer");
    serverClient = new OneDirectionTunnel(serverSocket, clientSocket, "serverClient");
  }

  public void spawnTunnelThreads() {
    // Start both of them in their own threads.
    clientServer.start();
    serverClient.start();
  }

  public boolean isClosed() {
    return clientSocket.isClosed() && serverSocket.isClosed();
  }
}
