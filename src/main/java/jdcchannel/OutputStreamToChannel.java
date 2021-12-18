package jdcchannel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

/** Converts an OutputStream to a non-blocking WritableByteChannel.
 *
 * An internal FIFO queue is used to store data written by the caller, and a separate
 * thread then writes it to the OutputStream.
 *
 * The write(ByteBuffer src) may return a 0 - no bytes written - if the internal buffer fills
 * faster than it can be written to the OutputStream. The caller must handle this situation.
 *
 */
public class OutputStreamToChannel implements WritableByteChannel, Runnable {

  private String                        label;
  private OutputStream                  strm;
  private int                           maxInFlight;
  private int                           maxWriteSz;
  private Delay                         delay;

  private ConcurrentLinkedDeque<byte[]> queue = new ConcurrentLinkedDeque<byte[]>();
  private AtomicInteger                 inFlight = new AtomicInteger(0);

  private volatile Thread               thrd=null;
  private volatile Exception            ex = null;        // If the OutputStream throws an Exception

  private AtomicInteger                  cntSent = new AtomicInteger(0);    // How many writes to OutputStream
  private AtomicInteger                  dataSent = new AtomicInteger(0);   // Total data sent to OutputStream
  /**
   *
   * @param strm          - The destination OutputStream
   * @param maxInFlight   - Max data in flight, after this write(ByteBuffer) returns 0 bytes written. 0 == infinite (not recommended).
   * @param maxWriteSz    - Will accumulate existing buffers up to this limit before calling the OutputStream.
   *                        0 == none, send each write(ByteBuffer src) data as given.
   *                        NOTE: If data longer than this is passed to write(...), then it will be sent as-is.
   * @param sleepStep     - Params for the Delay - see the docs there.
   * @param sleepMax      - Ditto
   * @param sleepDoubling - Ditto
   */
  public OutputStreamToChannel(String label, OutputStream strm, int maxInFlight, int maxWriteSz, int sleepStep, int sleepMax, boolean sleepDoubling) {
    this.label        = label;
    this.strm         = strm;
    this.maxInFlight  = maxInFlight;
    this.maxWriteSz   = maxWriteSz;
    this.delay        = new Delay(sleepStep, sleepMax, sleepDoubling);

    thrd              = new Thread(this);
    thrd.start();
  }
  /** Constructor with OutputStream and size of staging buffer */
  public OutputStreamToChannel(String label, OutputStream strm, int maxInFlight, int maxWriteSz) {
    this(label, strm, maxInFlight, maxWriteSz, 8, 256, true);
  }
  /** Constructor specifying only the OutputStream */
  public OutputStreamToChannel(String label, OutputStream strm){
    this(label, strm, 4096, 1024);
  }

  /** Number of actual writes to the OutputStream */
  public int getWrites()                  { return cntSent.get(); }
  /** Total number of bytes sent to OutputStream */
  public int getDataSent()                { return dataSent.get(); }

  public OutputStream getOutputStream()   { return strm; }
  public boolean isOpen()                 { return thrd != null; }
  public boolean hadError()               { return ex != null; }
  public Exception getException()         { return ex; }

  public void close() throws IOException  {
    while(inFlight.get() > 0) delay.delay();
    strm.close();
    thrd = null;
  }

  public int write(ByteBuffer data) throws IOException {
    if(ex!=null){
      if(ex instanceof IOException ) throw (IOException) ex;
      else throw new IllegalStateException("Had Exception: " + ex.toString());
    }
    if(!isOpen()) throw new ClosedChannelException();

    int sending = data.remaining();
    if(inFlight.get() + sending > maxInFlight)
      return 0;
    else {
      byte[] bfr = new byte[sending];
      data.get(bfr);
      queue.add(bfr);
      inFlight.addAndGet(sending);
      return sending;
    }
  }

  // Write from the queue to the OutputStream.
  public void run() {
    byte[] bfrCombine = maxInFlight > 0 ? new byte[maxInFlight] : null;

    while(thrd != null) try {
      if(queue.isEmpty())
        delay.delay();
      else {
        byte[] peek = queue.peek();               // Must be non-null since Q not empty
        int amtSent = peek.length;

        if (maxInFlight <= 0) {                   // Always send each block without accumulating data
          strm.write(peek);                       // May block
        } else if(peek.length >= maxWriteSz){     // Special case - single blocks sent even if large
          strm.write(peek);
        } else {
          // Check cases to see if we can just send this first buffer
          byte[] prev = peek;
          queue.removeFirst();          // Will at least send this first one
          peek = queue.peek();          // And look at the next one
          if(peek==null){               // no more in Q
            strm.write(prev);           // ... so just send this one
          } else if((prev.length + peek.length) > maxWriteSz){
            strm.write(prev);
            peek = null;                // So we don't remove first from Q
          } else {
            amtSent = 0;
            while(peek != null){        // Accumulate several in the buffer, then send it
              System.arraycopy(prev, 0, bfrCombine, amtSent, prev.length);
              amtSent += prev.length;
              peek = queue.peek();
              if(peek==null || (amtSent + peek.length) > maxWriteSz ){
                strm.write(bfrCombine, 0, amtSent);
                peek = null;              // So finish code below does not remove from Q
              } else {
                prev = peek;
                queue.removeFirst();
              }
            }
          }
        }

        if( peek != null ) queue.removeFirst();
        cntSent.incrementAndGet();
        dataSent.addAndGet(amtSent);
        inFlight.addAndGet( -amtSent );
        delay.reset();
      }
    } catch (Exception e){
      ex = e;
      thrd = null;
    }
  }
}
