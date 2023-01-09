package org.jboss.logmanager.handlers;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;

import javax.net.ssl.SSLContext;

import org.jboss.logmanager.AssertingErrorManager;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.logmanager.config.FormatterConfiguration;
import org.jboss.logmanager.config.HandlerConfiguration;
import org.jboss.logmanager.config.LogContextConfiguration;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.SocketHandler.Protocol;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class SocketHandlerTests extends AbstractHandlerTest {

    private final InetAddress address;

    // https://bugs.openjdk.java.net/browse/JDK-8219991
    private final String JAVA_VERSION = System.getProperty("java.version");
    private final String JDK_8219991_ERROR_MESSAGE = "https://bugs.openjdk.java.net/browse/JDK-8219991";
    private final Boolean JDK_8219991 = JAVA_VERSION.startsWith("1.8") || (JAVA_VERSION.startsWith("11.0.8") && System.getProperty("java.vendor").contains("Oracle"));

    public SocketHandlerTests() throws UnknownHostException {
        address = InetAddress.getByName(System.getProperty("org.jboss.test.address", "127.0.0.1"));
    }

    @Test
    public void testTcpConnection() throws Exception {
        try (
                SimpleServer server = SimpleServer.createTcpServer();
                SocketHandler handler = createHandler(Protocol.TCP, server.getPort())
        ) {
            final ExtLogRecord record = createLogRecord("Test TCP handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler", msg);
        }
    }

    @Test
    public void testTlsConnection() throws Exception {
        try (
                SimpleServer server = SimpleServer.createTlsServer();
                SocketHandler handler = createHandler(Protocol.SSL_TCP, server.getPort())
        ) {
            final ExtLogRecord record = createLogRecord("Test TLS handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TLS handler", msg);
        }
    }

    @Test
    public void testUdpConnection() throws Exception {
        try (
                SimpleServer server = SimpleServer.createUdpServer();
                SocketHandler handler = createHandler(Protocol.UDP, server.getPort())
        ) {
            final ExtLogRecord record = createLogRecord("Test UDP handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test UDP handler", msg);
        }
    }

    @Test
    public void testTcpPortChange() throws Exception {
        try (
                SimpleServer server1 = SimpleServer.createTcpServer();
                SimpleServer server2 = SimpleServer.createTcpServer();
                SocketHandler handler = createHandler(Protocol.TCP, server1.getPort())
        ) {
            ExtLogRecord record = createLogRecord("Test TCP handler " + server1.getPort());
            handler.doPublish(record);
            String msg = server1.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler " + server1.getPort(), msg);

            // Change the port on the handler which should close the first connection and open a new one
            handler.setPort(server2.getPort());
            record = createLogRecord("Test TCP handler " + server2.getPort());
            handler.doPublish(record);
            msg = server2.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler " + server2.getPort(), msg);

            // There should be nothing on server1, we won't know if the real connection is closed but we shouldn't
            // have any data remaining on the first server
            Assert.assertNull("Expected no data on server1", server1.peek());
        }
    }

    @Test
    public void testProtocolChange() throws Exception {
        Assume.assumeFalse(JDK_8219991_ERROR_MESSAGE, JDK_8219991);
        // keep handler for whole test
        SocketHandler handler;
        try (SimpleServer server = SimpleServer.createTcpServer()) {
            handler = createHandler(Protocol.TCP, server.getPort());
            final ExtLogRecord record = createLogRecord("Test TCP handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler", msg);
        }
        // wait until the OS really release used port. https://issues.redhat.com/browse/LOGMGR-314
        Thread.sleep(50);

        // Change the protocol on the handler which should close the first connection and open a new one
        handler.setProtocol(Protocol.SSL_TCP);

        try (SimpleServer server = SimpleServer.createTlsServer(handler.getPort())) {
            final ExtLogRecord record = createLogRecord("Test TLS handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TLS handler", msg);
        }
        // close the handler
        handler.close();
    }

    @Test
    public void testTcpReconnect() throws Exception {
        // keep handler for whole test
        SocketHandler handler;
        try (
                SimpleServer server = SimpleServer.createTcpServer()
        ) {
            handler = createHandler(Protocol.TCP, server.getPort());
            handler.setErrorManager(AssertingErrorManager.of(ErrorManager.FLUSH_FAILURE));

            // Publish a record to a running server
            final ExtLogRecord record = createLogRecord("Test TCP handler");
            handler.doPublish(record);
            final String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler", msg);
        }
        // wait until the OS really release used port. https://issues.redhat.com/browse/LOGMGR-314
        Thread.sleep(50);

        // Publish a record to a down server, this likely won't put the handler in an error state yet. However once
        // we restart the server and loop the first socket should fail before a reconnect is attempted.
        final ExtLogRecord record = createLogRecord("Test TCP handler");
        handler.doPublish(record);
        try (
                SimpleServer server = SimpleServer.createTcpServer(handler.getPort())
        ) {
            // Keep writing a record until a successful record is published or a timeout occurs
            final String msg = timeout(() -> {
                final ExtLogRecord r = createLogRecord("Test TCP handler");
                handler.doPublish(r);
                try {
                    return server.poll();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }, 10);
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler", msg);
        }
        // close the handler
        handler.close();
    }

    @Test
    public void testTlsConfig() throws Exception {
        Assume.assumeFalse(JDK_8219991_ERROR_MESSAGE, JDK_8219991);
        try (SimpleServer server = SimpleServer.createTlsServer()) {
            final LogContext logContext = LogContext.create();
            final LogContextConfiguration logContextConfiguration = LogContextConfiguration.Factory.create(logContext);
            // Create the formatter
            final FormatterConfiguration formatterConfiguration = logContextConfiguration.addFormatterConfiguration(
                    null, PatternFormatter.class.getName(), "pattern");
            formatterConfiguration.setPropertyValueString("pattern", "%s\n");
            // Create the handler
            final HandlerConfiguration handlerConfiguration = logContextConfiguration.addHandlerConfiguration(
                    null, SocketHandler.class.getName(), "socket",
                    "protocol", "hostname", "port");
            handlerConfiguration.setPropertyValueString("protocol", Protocol.SSL_TCP.name());
            handlerConfiguration.setPropertyValueString("hostname", address.getHostAddress());
            handlerConfiguration.setPropertyValueString("port", Integer.toString(server.getPort()));
            handlerConfiguration.setPropertyValueString("autoFlush", "true");
            handlerConfiguration.setPropertyValueString("encoding", "utf-8");
            handlerConfiguration.setFormatterName(formatterConfiguration.getName());

            logContextConfiguration.addLoggerConfiguration("").addHandlerName(handlerConfiguration.getName());

            logContextConfiguration.commit();

            final Handler instance = handlerConfiguration.getInstance();
            Assert.assertTrue(instance instanceof SocketHandler);
            ((SocketHandler) instance).setSocketFactory(SSLContext.getDefault().getSocketFactory());

            // Create the root logger
            final Logger logger = logContext.getLogger("");
            logger.info("Test TCP handler " + server.getPort() + " 1");
            String msg = server.timeoutPoll();
            Assert.assertNotNull(msg);
            Assert.assertEquals("Test TCP handler " + server.getPort() + " 1", msg);
        }
    }

    private SocketHandler createHandler(final Protocol protocol, int port) throws UnsupportedEncodingException {
        final SocketHandler handler = new SocketHandler(protocol, address, port);
        handler.setAutoFlush(true);
        handler.setEncoding("utf-8");
        handler.setFormatter(new PatternFormatter("%s\n"));
        handler.setErrorManager(AssertingErrorManager.of());
        return handler;
    }

    private static <R> R timeout(final Supplier<R> supplier, final int timeout) throws InterruptedException {
        R value = null;
        long t = timeout * 1000;
        final long sleep = 100L;
        while (t > 0) {
            final long before = System.currentTimeMillis();
            value = supplier.get();
            if (value != null) {
                break;
            }
            t -= (System.currentTimeMillis() - before);
            TimeUnit.MILLISECONDS.sleep(sleep);
            t -= sleep;
        }
        Assert.assertFalse(String.format("Failed to get value in %d seconds.", timeout), (t <= 0));
        return value;
    }
}
