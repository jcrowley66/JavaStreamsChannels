package jdcchannels;

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
 * - isBlocking and configureBlocking methods are essentially ignored (and cannot be overridden!). This
 *   SocketChannel will always operate in a non-blocking mode.
 *
 * Creates a READ Thread and a WRITE Thread to handle the read(...) & write(...) methods as non-blocking. Caller
 * must be able to handle a read(bfr) or write(bfr) which returns 0 bytes processed. Read may also return -1 == EOF
 */

public class SocketToSocketChannel extends SocketChannel {
  private String                label;
  private Socket                skt;
  public InputStreamToChannel   instrm;
  private OutputStreamToChannel outstrm;

  int rdMaxInFlight;  int rdMaxBfr;   int rdSleepStep; int rdSleepMax; boolean rdSleepByDoubling;
  int wrtMaxInFlight; int wrtMaxSize; int wrtSleepStep; int wrtSleepMax; boolean wrtSleepByDoubling;

  /** Facade to taking a regular Socket and generate non-blocking data transfers.
   *
   * Data is read from the InputStream, or written to the OutputStream using internal buffers so that read(...) and
   * write(...) operations do not block the caller - but may return n == 0 bytes were transferred, and the caller
   * must handle this (usually in some sort of delay loop or posting an event to be re-tried in X milliseconds).
   *
   * @param label             - user-defined label for this SocketChannel
   * @param socket            - the base Socket to treat as a Channel
   * @param selectorProvider  - NOT USED - passed to Channel, but this does NOT support Selector logic
   * @param rdMaxInFlight     - Max amount of in-process data before stop reading InputStream until some is consumed
   * @param rdMaxBuffer       - Max size of InputStream buffer. Note: May exceed rdMaxInFlight if current max + next read exceeds.
   * @param rdSleepStep       - Delay parameters for processing InputStream - see Delay
   * @param rdSleepMax        - ditto
   * @param rdSleepByDoubling - ditto
   * @param wrtMaxInFlight    - Max output data in-process for the OutputStream. If full, write(...) returns 0 bytes transferred
   * @param wrtMaxSize        - Smaller write(...) payloads are accumulated and sent in a buffer <= this size
   *                            Note: - 0 == send every write(...) to OutputStream separately (not recommended)
   *                                  - if a single write(...) payload is larger, it is sent as-is
   * @param wrtSleepStep      - Delay parameters for processing OutputStream - see Delay
   * @param wrtSleepMax       - ditto
   * @param wrtSleepByDoubling- ditto
   *
   * @throws IOException
   */
  public SocketToSocketChannel(String label, Socket socket, SelectorProvider selectorProvider,
                               int rdMaxInFlight, int rdMaxBuffer, int rdSleepStep, int rdSleepMax, boolean rdSleepByDoubling,
                               int wrtMaxInFlight, int wrtMaxSize, int wrtSleepStep, int wrtSleepMax, boolean wrtSleepByDoubling) throws IOException {
    super(selectorProvider);
    if(!socket.isConnected()) throw new IllegalStateException("The Socket must already be connected.");

    this.label              = label;
    this.skt                = socket;
    this.rdMaxInFlight      = rdMaxInFlight;
    this.rdMaxBfr           = rdMaxBuffer;
    this.rdSleepStep        = rdSleepStep;
    this.rdSleepMax         = rdSleepMax;
    this.rdSleepByDoubling  = rdSleepByDoubling;
    this.wrtMaxInFlight     = wrtMaxInFlight;
    this.wrtMaxSize         = wrtMaxSize;
    this.wrtSleepStep       = wrtSleepStep;
    this.wrtSleepMax        = wrtSleepMax;
    this.wrtSleepByDoubling = wrtSleepByDoubling;

    configureBlocking(false);

    instrm  = new InputStreamToChannel(label + " InStrm", skt.getInputStream(), rdMaxInFlight, rdMaxBfr, rdSleepStep, rdSleepMax, rdSleepByDoubling);
    outstrm = new OutputStreamToChannel(label + " OutStrm", skt.getOutputStream(), wrtMaxInFlight, wrtMaxSize, wrtSleepStep, wrtSleepMax, wrtSleepByDoubling);
  }
  /** CONSTRUCTOR - defaults for all buffer sizes and delays */
  public SocketToSocketChannel(String label, Socket socket, SelectorProvider provider) throws IOException {
    this(label, socket, provider, 1024 * 10, 1024 * 10, 8, 256, true, 4096, 1024, 8, 256, true);
  }
  /** CONSTRUCTOR - specify buffer sizes, defaults for all other parameters */
  public SocketToSocketChannel(String label, Socket socket, SelectorProvider provider, int rdMaxInFlight, int wrtMaxInFlight, int wrtMaxSize) throws IOException {
    this(label, socket, provider, rdMaxInFlight, 1024, 8, 256, true, wrtMaxInFlight, wrtMaxSize, 8, 256, true);
  }
  /** CONSTRUCTOR - provide only the Socket, all other parameters are defaulted */
  public SocketToSocketChannel(String label, Socket socket) throws IOException {
    this(label, socket, null);
  }

  /** Read from the Socket into the ByteBuffer. Return 0 if not data available, -1 if EOF */
  public int read(ByteBuffer dst) throws IOException {
    return instrm.read(dst);
  }
  /** Write from the ByteBuffer to the Socket, returns 0 if no bytes written and caller must handle */
  public int write(ByteBuffer src) throws IOException {
    return outstrm.write(src);
  }

  /** Amount of data currently staged in the InputStream internal buffer. NOTE: Returns -1 if EOF **/
  public int inInFlight() { return instrm.inFlight(); }
  public int available()  { return inInFlight(); }
  /** Number of physical reads completed on the InputStream */
  public int numReads()   { return instrm.numReads(); }
  /** Total data read from the InputStream */
  public int dataRead()   { return instrm.dataRead(); }
  /** The number of physical writes completed to the OutputStream. */
  public int numWrites()   { return outstrm.numWrites(); }
  /** Total number of bytes sent to the OutputStream */
  public int dataSent()    { return outstrm.dataSent(); }
  /** Number of bytes queued to be sent to the OutputStream */
  public int outInFlight() { return outstrm.inFlight(); }

  // Supported methods that are simple pass-through to the Socket or basic implementation
  public Socket socket()                                      { return skt; }
  public boolean isConnected()                                { return skt.isConnected(); }
  public boolean isConnectionPending()                        { return !skt.isConnected(); }
  public SocketAddress getRemoteAddress() throws IOException  { return skt.getRemoteSocketAddress(); }
  public SocketAddress getLocalAddress() throws IOException   { return skt.getLocalSocketAddress(); }
  public SocketChannel shutdownInput() throws IOException     { skt.shutdownInput(); return this; }
  public SocketChannel shutdownOutput() throws IOException    { skt.shutdownOutput(); return this; }

  protected void implConfigureBlocking(boolean block) throws IOException { return; }

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
}
