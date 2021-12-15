package jdcchannel;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

/** Takes a Socket and provides a non-blocking SocketChannel. This does NOT use the selector logic!
 *
 * WARNING
 * -------
 * - Expects the Socket to already be set up and connected. Otherwise throws an exception from Constructor.
 * - Constructor throws IOException if the getInputStream or getOutputStream of Socket throw
 * - Only supports the read(ByteBuffer dst) and write(ByteBuffer src) methods plus some that are simple
 *    pass-through calls to the Socket. All of the rest will throw an exception if called.
 *
 * Creates a READ Thread and a WRITE Thread to handle the read(...) & write(...) methods as non-blocking. Caller
 * must be able to handle a read(bfr) or write(bfr) which returns 0 bytes processed. Read may also return -1 == EOF
 */

public class SocketToSocketChannel extends SocketChannel {
  private Socket                skt;
  private InputStreamToChannel  instrm;
  private OutputStreamToChannel outstrm;

  int rdBfrSz; int rdSleepStep; int rdSleepMax; boolean rdSleepByDoubling;
  int wrtBfrSz; int wrtSleepStep; int wrtSleepMax; boolean wrtSleepByDoubling;

  /** CONSTRUCTOR -- provide all parameters */
  public SocketToSocketChannel(Socket socket, SelectorProvider selectorProvider, 
                               int rdBfrSz, int rdSleepStep, int rdSleepMax, boolean rdSleepByDoubling,
                               int wrtBfrSz, int wrtSleepStep, int wrtSleepMax, boolean wrtSleepByDoubling) throws IOException {
    super(selectorProvider);

    if(!socket.isConnected()) throw new IllegalStateException("The Socket must already be connected.");

    this.skt                = socket;
    this.rdBfrSz            = rdBfrSz;
    this.rdSleepStep        = rdSleepStep;
    this.rdSleepMax         = rdSleepMax;
    this.rdSleepByDoubling  = rdSleepByDoubling;
    this.wrtBfrSz           = wrtBfrSz;
    this.wrtSleepStep       = wrtSleepStep;
    this.wrtSleepMax        = wrtSleepMax;
    this.wrtSleepByDoubling = wrtSleepByDoubling;

    instrm = new InputStreamToChannel(skt.getInputStream(), rdBfrSz, rdSleepStep, rdSleepMax, rdSleepByDoubling);
    outstrm = new OutputStreamToChannel(skt.getOutputStream(), wrtBfrSz, wrtSleepStep, wrtSleepMax, wrtSleepByDoubling);

  }
  /** CONSTRUCTOR - defaults for all buffer sizes and delays */
  public SocketToSocketChannel(Socket socket, SelectorProvider provider) throws IOException {
    this(socket, provider, 4096, 8, 256, true, 4096, 8, 256, true);
  }
  /** CONSTRUCTOR - provide only the Socket, all other parameters are defaulted */
  public SocketToSocketChannel(Socket socket) throws IOException {
    this(socket, null);
  }

  /** Read from the Socket into the ByteBuffer */
  public int read(ByteBuffer dst) throws IOException {
    return instrm.read(dst);
  }
  /** Write from the ByteBuffer to the Socket */
  public int write(ByteBuffer src) throws IOException {
    return outstrm.write(src);
  }

  // Supported methods that are simple pass-through to the Socket
  public Socket socket()                                      { return skt; }
  public boolean isConnected()                                { return skt.isConnected(); }
  public boolean isConnectionPending()                        { return !skt.isConnected(); }
  public SocketAddress getRemoteAddress() throws IOException  { return skt.getRemoteSocketAddress(); }
  public SocketAddress getLocalAddress() throws IOException   { return skt.getLocalSocketAddress(); }
  public SocketChannel shutdownInput() throws IOException     { skt.shutdownInput(); return this; }
  public SocketChannel shutdownOutput() throws IOException    { skt.shutdownOutput(); return this; }

  /************************* Methods NOT Supported **********************/
  private void throwNYI() { throw new IllegalStateException("NOT YET IMPLEMENTED"); }

  public SocketChannel bind(SocketAddress local) throws IOException                     { throwNYI(); return null; }
  public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException  { throwNYI(); return null;}
  public <T> T getOption(SocketOption<T> name) throws IOException                       { throwNYI(); return null;}
  public Set<SocketOption<?>> supportedOptions()                                        { throwNYI(); return null; }
  public boolean connect(SocketAddress remote) throws IOException                       { throwNYI(); return false; }
  public boolean finishConnect() throws IOException                                     { throwNYI(); return false; }
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException        { throwNYI(); return -1; }
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException       { throwNYI(); return -1; }
  protected void implCloseSelectableChannel() throws IOException                        { throwNYI(); return ; }
  protected void implConfigureBlocking(boolean block) throws IOException                { throwNYI(); return; }


}
