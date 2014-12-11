/**
 * Copyright Altiscale 2014
 * Author: Zoran Dimitrijevic <zoran@altiscale.com>
 *
 * TcpProxyServer is a server that manages one listening port and for each incoming TCP
 * connection to that port it creates another TCP connection to one of pre-set destinations
 * specified by an array of host:port (string:int) pairs.
 *
 * Goal of TcpProxy is to simply tunnel all bytes from incoming socket to its destination socket,
 * trying to load-balance so that each destination connection transfers similar amount of data.
 *
 * Destinations are usually ssh-tunnels connected to a single destination host:port.
 *
 **/

package com.altiscale.TcpProxy;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import com.altiscale.Util.SecondMinuteHourCounter;
import com.altiscale.Util.ServerStatus;
import com.altiscale.Util.ServerWithStats;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class TcpProxyServer implements ServerWithStats {

  protected class HostPort {
    String host;
    int port;

    public HostPort(String host, int port) {
      this.host = host;
      this.port = port;
    }

    public String toString() {
      return "" + host + ":" + port;
    }
  }

  protected class ProxyConfiguration {
    int listeningPort;
    ArrayList<HostPort> serverHostPortList;

    public ProxyConfiguration(int port) {
      listeningPort = port;
      serverHostPortList = new ArrayList<HostPort>();
    }

    public void parseServerStringAndAdd(String server) throws URISyntaxException {
      // WORKAROUND: add any scheme to make the resulting URI valid.
      URI uri = new URI("my://" + server); // may throw URISyntaxException
      String host = uri.getHost();
      int port = uri.getPort();
      if (uri.getHost() == null || uri.getPort() == -1) {
        throw new URISyntaxException(uri.toString(), "URI must have host and port parts");
      }
      serverHostPortList.add(new HostPort(host, port));
    }
  }

  protected class Server {
    HostPort hostPort;

    SecondMinuteHourCounter requestCnt;
    SecondMinuteHourCounter openedCnt;
    SecondMinuteHourCounter closedCnt;
    SecondMinuteHourCounter byteRateCnt;

    public Server(HostPort hostPort) {
      this.hostPort = hostPort;
      requestCnt = new SecondMinuteHourCounter("requestCnt " + hostPort.toString());
      openedCnt = new SecondMinuteHourCounter("openedCnt " + hostPort.toString());
      closedCnt = new SecondMinuteHourCounter("closedCnt " + hostPort.toString());
      byteRateCnt = new SecondMinuteHourCounter("byteRateCnt " + hostPort.toString());
    }

    public void incrementOpenedConn() {
      openedCnt.increment();
    }

    public void incrementClosedConn() {
      closedCnt.increment();
    }

    public void incrementByteRateBy(long amount) {
      byteRateCnt.incrementBy(amount);
    }

    public void establishTunnel(Socket clientSocket) throws java.io.IOException {
      requestCnt.increment();
      Socket serverSocket = new Socket(hostPort.host, hostPort.port);
      LOG.info("Setting tunnel between [" +
          clientSocket.getInetAddress().getHostAddress() + ":" +
          clientSocket.getPort() + "] and server [" +
          hostPort.host + ":" + hostPort.port + "]");
      TcpTunnel tunnel = new TcpTunnel(clientSocket, serverSocket, this);

      // Create threads that will handle this tunnel.
      tunnel.spawnTunnelThreads();
    }
  }

  // log4j logger.
  private static Logger LOG = Logger.getLogger("TcpProxy");

  // Config for this proxy.
  private ProxyConfiguration config;

  // Port number where we want to listen for our clients.
  private int tcpProxyPort;

  // This is our ServerSocket running on tcpProxyPort.
  private ServerSocket tcpProxyService;

  // List of all servers we can use to tunnel our client trafic. We choose from this list
  // based on our load-balancing algorithm, and if we cannot connect we retry using next
  // server until we establish the tunnel.
  private ArrayList<Server> serverList;

  // Used by round-robin load-balancing algorithm.
  private int nextServerId = 0;

  private String name;

  @Override
  public String getServerStatsHtml() {
    long lastSecondByteRate = 0;
    long lastMinuteByteRate = 0;
    long lastHourByteRate = 0;
    long openedConnections = 0;
    long closedConnections = 0;
    for (Server server : serverList) {
      openedConnections += server.openedCnt.getTotalCnt();
      closedConnections += server.closedCnt.getTotalCnt();
      lastSecondByteRate += server.byteRateCnt.getLastSecondCnt();
      lastMinuteByteRate += server.byteRateCnt.getLastMinuteCnt();
      lastHourByteRate += server.byteRateCnt.getLastHourCnt();
    }

    String htmlServerStats = "";
    htmlServerStats += "HTTP/1.0 200 OK\r\n";
    htmlServerStats += "\r\n";
    htmlServerStats += "<head><meta http-equiv=\"refresh\" content=\"5\" /></head>\r\n";
    htmlServerStats += "<style> table, th, td { padding: 3px; border: 1px solid black;" +
                       " border-collapse: collapse; text-align: right;} </style>\r\n";
    htmlServerStats += "<TITLE>" + getServerName() + " Status</TITLE>\r\n";

    htmlServerStats += "<b>" + getServerName() + "</b> - " + tcpProxyPort + "<br/><br/><br/>\r\n";

    htmlServerStats += "<table>\r\n";
    htmlServerStats += "<tr><td><b>counters</b></td><td><b>values</b></td></tr>\r\n";

    htmlServerStats += "<tr><td>Open connections</td><td>" + (openedConnections - closedConnections) +
                       "</td></tr>\r\n";

    htmlServerStats += "<tr><td><b>server</b> byte rate</td><td>" +
                       "<table><tr>" +
                       "<td>" + lastSecondByteRate + " B/s</td>" +
                       "<td>" + lastMinuteByteRate + " B/min</td>" +
                       "<td>" + lastHourByteRate + " B/h</td>" +
                       "</tr></table>";
 
    for (Server server : serverList) {
      htmlServerStats += "<tr><td><b>" + server.hostPort.toString() + "</b> byte rate </td><td>" +
                         "<table><tr>" +
                         "<td>" + server.byteRateCnt.getLastSecondCnt() + " B/s</td>" +
                         "<td>" + server.byteRateCnt.getLastMinuteCnt() + " B/min</td>" +
                         "<td>" + server.byteRateCnt.getLastHourCnt() + " B/h</td>" +
                         "</tr></table>" +
                         "</td></tr>\r\n";
    }

    htmlServerStats += "<tr><td>opened connections</td><td>" + openedConnections +
                       "</td></tr>\r\n";
    htmlServerStats += "<tr><td>closed connections</td><td>" + closedConnections +
                       "</td></tr>\r\n";
    htmlServerStats += "</table>\r\n";

    return htmlServerStats;
  }

  public TcpProxyServer(String name) {
    this.name = name;
    serverList = new ArrayList<Server>();
  }

  public void init(ProxyConfiguration conf) {
    config = conf;

    for (HostPort serverHostPort : config.serverHostPortList) {
      serverList.add(new Server(serverHostPort));
    }

    tcpProxyPort = config.listeningPort;
    try {
      tcpProxyService = new ServerSocket(tcpProxyPort);
      LOG.info("Listening for incoming clients on port " + tcpProxyPort);
    } catch (IOException ioe) {
      LOG.error("IO exception while establishing proxy service on port " + tcpProxyPort);
      System.exit(1);
    }
  }

  public ArrayList<Server> getServerList() {
    return serverList;
  }

  public Server getRoundRobinServer() {
    nextServerId = (nextServerId + 1) % serverList.size();
    return serverList.get(nextServerId);
  }

  public void setupTunnel(Socket clientSocket) {
    final int RETRY_MAX = 3;
    for (int i = 0; i < RETRY_MAX; i++) {
      Server server = getRoundRobinServer();
      try {
        server.establishTunnel(clientSocket);
        break;
      } catch (IOException ioe) {
        LOG.error("Error while connecting to server " +
                  server.hostPort.host + ":" + server.hostPort.port);
      }
    }
  }

  public void runListeningLoop() {
    while (!tcpProxyService.isClosed()) {
      try {
        Socket clientSocket = null;
        clientSocket = tcpProxyService.accept();
        if (null != clientSocket) {
          setupTunnel(clientSocket);
        }
      } catch (IOException ioe) {
        LOG.error("IOException while accepting connection: " + ioe.getMessage());
      }
    }
  }

  public String usage() {
    return "Usage: tcpProxy -port <proxyPort> " + 
           "-status-port <status_port> -servers [<server_name>:<server_port>]+";
  }

  public String getServerName() {
    return name;
  }

  public static Options getCommandLineOptions() {
    Options options = new Options();

    //options.addOption("v", "verbose", false, "Verbose logging.");

    options.addOption(OptionBuilder.withLongOpt("port")
                                   .withDescription("Listening port for proxy clients.")
                                   .withType(Number.class)
                                   .hasArg()
                                   .create());

    options.addOption(OptionBuilder.withLongOpt("status-port")
                                   .withDescription("Port for proxy status in html format.")
                                   .withType(Number.class)
                                   .hasArg()
                                   .create());

    options.addOption(OptionBuilder.withLongOpt("servers")
                                   .withDescription("Server/servers for the proxy to connect to" +
                                                    " in server:port format.")
                                   .hasArgs()
                                   .withValueSeparator(' ')
                                   .create());

    return options; 
  }

  public static void main (String[] args) {
    TcpProxyServer proxy = new TcpProxyServer("TcpProxy");

    // Create the options.
    Options options = getCommandLineOptions();

    CommandLine commandLine = null;
    try {
      commandLine = new GnuParser().parse(options, args);
    } catch (org.apache.commons.cli.ParseException e) {
      LOG.info("Parsing exception" + e.getMessage());
      LOG.info(proxy.usage());
      System.exit(1);
    }
 
    // TODO(zoran): enable users to specify one hostname and multiple ports.
    int listeningPort = 12345; // default value
    if (commandLine.hasOption("port")) {
       listeningPort = Integer.parseInt(commandLine.getOptionValue("port"));
    }
    ProxyConfiguration conf = proxy.new ProxyConfiguration(listeningPort);

    int statusPort = 1982;
    if (commandLine.hasOption("status-port")) {
      statusPort =  Integer.parseInt(commandLine.getOptionValue("status-port"));
    }

    // Launch ServerStats thread.
    new Thread(new ServerStatus(proxy, statusPort)).start();


    // Add servers.
    String[] servers = commandLine.getOptionValues("servers");
    try {
      for (String server : servers) {
        conf.parseServerStringAndAdd(server);
      }
    } catch (URISyntaxException e) {
      LOG.error("Server path parsing exception " + e.getMessage());
      System.exit(1);
    }

    proxy.init(conf);

    if (proxy.getServerList().size() < 1) {
      LOG.error("No server specified.");
      System.exit(1);
    }

    // Loop on the listen port forever.
    proxy.runListeningLoop();
  }
}
