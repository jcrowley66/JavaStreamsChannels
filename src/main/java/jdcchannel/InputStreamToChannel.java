package jdcchannel;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Converts an InputStream to a non-blocking ReadableByteChannel. The InputStream must already be Open.
 *
 *  An internal FIFO queue reads and stages the data from the InputStream on a separate thread.
 *
 *  NOTE: The caller should be prepared for 'read' returning 0 bytes since it does not block.
 **/
public class InputStreamToChannel implements ReadableByteChannel, Runnable {

  private final boolean bDebug = false;
  private final boolean bRead  = bDebug && true;
  private final boolean bThread= bDebug && true;

  private String      label;
  private InputStream strm;
  private int         maxInFlight;
  private byte[]      bfr;
  private Delay       delay;

  private ConcurrentLinkedDeque<byte[]> queue    = new ConcurrentLinkedDeque<byte[]>();
  private AtomicInteger                 rdAvail  = new AtomicInteger(0);   // Amount of DATA available
  private volatile Thread               thrd     = null;      // When set back to null, at EOF
  private volatile Exception            ex       = null;      // If the InputStream throws an Exception

  /** CONSTRUCTOR - all parameters specified
   *
   * @param label           - caller assigned label for this Channel
   * @param strm            - the InputStream to read
   * @param maxInFlight     - max data to hold in the Q. If exceeded, pause reading the InputStream
   *                          0 == no max (not recommended)
   * @param rdBfrSz         - Size of the input buffer used to read the InputStream
   * @param sleepStep       - Parameters for the Delay, used if have exceeded maxInFlight in the Q. See Delay
   * @param sleepMax        - Ditto
   * @param sleepByDoubling - Ditto
   */
  public InputStreamToChannel(String label, InputStream strm, int maxInFlight, int rdBfrSz, int sleepStep, int sleepMax, boolean sleepByDoubling) {
    this.label        = label;
    this.strm         = strm;
    this.maxInFlight  = maxInFlight;
    this.bfr          = new byte[rdBfrSz];
    this.delay        = new Delay(sleepStep, sleepMax, sleepByDoubling);

    thrd              = new Thread(this);
    thrd.start();
  }
  /** Constructor with InputStream and maxInFlight - default Delay settings */
  public InputStreamToChannel(String label, InputStream strm, int maxInFlight, int rdBfrSz) {
    this(label, strm, maxInFlight, rdBfrSz, 8, 256, true);
  }

  /** Constructor specifying only the Label & InputStream */
  public InputStreamToChannel(String label, InputStream strm){
    this(label, strm, 4096, 1024);
  }

  public String getLabel()            { return label; }
  /** Return the original InputStream */
  public InputStream getInputStream() { return strm; }
  public boolean hadError()           { return ex != null; }
  public Exception getException()     { return ex; }

  /** Amount of data currently staged in the internal buffer
   *  NOTE: Returns -1 if EOF
   **/
  public int available() { return thrd==null ? -1 : rdAvail.get(); }

  public boolean isOpen() {
    return thrd!=null;
  }

  public void close() throws IOException {
    if(thrd != null) {
      strm.close();
      thrd = null;
      rdAvail.set(-1);
    }
  }

  public int read(ByteBuffer bb) throws IOException {
    if(ex!=null){
      if(ex instanceof IOException ) throw (IOException) ex;
      else throw new IllegalStateException("Had Exception: " + ex.toString());
    }

    int avail = rdAvail.get();
    if(thrd==null && avail <= 0) {
      rdAvail.set(-1);
      return -1;              // EOF
    }

    int want = bb.remaining();
    if(want <= 0) return 0;   // No space left in 'bb'

    // Read from Q and put out to the ByteBuffer - return number of bytes transferred, 0 if none

    int amtRead = 0;
    while(want > 0 && !queue.isEmpty()){
      byte[] bfr = queue.poll();
      if(bfr.length <= want){
        bb.put(bfr);
        want    -= bfr.length;
        amtRead += bfr.length;
      } else {
        // First item in Q is larger than space left in the request buffer
        // Copy 'want' bytes to output, make smaller byte[] and stick back on front of Q
        bb.put(bfr, 0, want);
        byte[] remains = new byte[bfr.length - want];
        System.arraycopy(bfr, want, remains, 0, bfr.length - want);
        queue.addFirst(remains);
        amtRead += want;
        want    = 0;
      }
    }
    rdAvail.addAndGet( -amtRead );
    return amtRead;
  }

  public void run() {
    if(bThread) debug("Started thread");
    // Read from the InputStream into the bfrRead array
    try {
      while (thrd != null) {
        if (maxInFlight > 0 && rdAvail.get() >= maxInFlight)
          delay.delay();
        else {
          int n = strm.read(bfr);               // May block
          if(n == -1)
            close();
          else if(n > 0){
            if(n == bfr.length) {
              queue.add(bfr);
              bfr = new byte[bfr.length];
            } else {
              queue.add(Arrays.copyOfRange(bfr, 0, n));
            }
            rdAvail.addAndGet(n);
            delay.reset();
          } else
            delay.delay();
        }
      }
    } catch (Exception e) {
      thrd = null;
      ex = e;
      return;
    }
  }

  private static void ln(String s) { System.out.println(s); }

  private void debug(String s) {
    if(bDebug){
      ln("DEBUG -- " + label + ": " + s);
    }
  }
}

