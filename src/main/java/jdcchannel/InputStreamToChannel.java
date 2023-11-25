package jdcchannel;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;

/** Converts an InputStream to a non-blocking ReadableByteChannel. The InputStream must already be Open.
 *
 *  An internal FIFO queue reads and stages the data from the InputStream on a separate thread.
 *
 *  NOTE: The caller should be prepared for 'read' returning 0 bytes since it does not block.
 **/
public class InputStreamToChannel extends InOutCommon implements ReadableByteChannel, Runnable {

  private InputStream strm;
  private int         maxInFlight;
  private int         rdBfrSz;
  private int         sleepStep;
  private int         sleepMax;
  private boolean     sleepByDoubling;

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
    this.label            = label;
    this.strm             = strm;
    this.maxInFlight      = maxInFlight;
    this.rdBfrSz          = rdBfrSz;
    this.sleepStep        = sleepStep;
    this.sleepMax         = sleepMax;
    this.sleepByDoubling  = sleepByDoubling;

    thrd                  = new Thread(this);
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

  public boolean isOpen() {
    return thrd!=null;
  }

  public void close() throws IOException {
    if(thrd != null) {
      strm.close();
      thrd = null;
      inFlight.set(-1);
    }
  }

  /** Read into the ByteBuffer and return the amount of data read, -1 if EOF.
   *
   * NOTE: Will return ZERO bytes read if no data is available and caller should handle.
   */
  public int read(ByteBuffer bb) throws IOException {
    if(bRdDtl){
      debugShowQueue("InputREAD", 99);
    }

    throwIfEx();

    int avail = inFlight.get();
    if(thrd==null && avail <= 0) {      // Closed the thread and no more data in flight
      inFlight.set(-1);
      return -1;              // EOF
    }
    synchronized(bb) {
      int want = bb.remaining();
      if (want <= 0) {
        if (bRead) debug("No space in ByteBuffer, returning 0");
        return 0;   // No space left in 'bb'
      }

      // Read from Q and put out to the ByteBuffer - return number of bytes transferred, 0 if none
      if (bRdDtl) {
        if (!queue.isEmpty()) {
          byte[] b = queue.peek();
          debug("READ Before -- Peek: " + debugShowItem(0, b));
          debug("READ Before -- Pos:" + bb.position() + ", Limit: " + bb.limit() + ", Want: " + want + ", Poll: " + b.length + ", Avail: " + inFlight.get() + ", QueueSz: " + queue.size());
        }
      }
      int amtRead = 0;
      while (want > 0 && !queue.isEmpty()) {
        byte[] bfrIn = queue.poll();
        if (bRdDtl) {
          debug("READ after POLL - " + debugShowItem(0, bfrIn));
        }
        if (bfrIn.length <= want) {
          bb.put(bfrIn);
          want -= bfrIn.length;
          amtRead += bfrIn.length;
        } else {
          // First item in Q is larger than space left in the request buffer
          // Copy 'want' bytes to output, make smaller byte[] and stick back on front of Q
          bb.put(bfrIn, 0, want);
          int remainAmt = bfrIn.length - want;
          byte[] remains = new byte[remainAmt];
          System.arraycopy(bfrIn, want, remains, 0, remainAmt);
          queue.addFirst(remains);
          if (bRdRmn) debugShowQueue(label, 128);
          amtRead += want;
          want = 0;
        }
      }
      inFlight.addAndGet(-amtRead);
      if (bRead) {
        if (amtRead != 0)
          debug("READ After -- AmtRead: " + amtRead + " bytes, Avail: " + inFlight.get() + ", #Reads: " + numReads.get() + ", TtlData: " + dataRead.get());
      }
      return amtRead;
    }
  }

  public void run() {
    if(bThread) debug("STARTED thread");
    byte[] bfr    = new byte[rdBfrSz];
    Delay  delay  = new Delay(sleepStep, sleepMax, sleepByDoubling);

    // Read from the InputStream into the bfrRead array
    try {
      while (thrd != null) {
        if (maxInFlight > 0 && inFlight.get() >= maxInFlight)
          delay.delay();
        else {
          int n = strm.read(bfr);               // May block
          if(bWrtDtl) debug("In THREAD, read " + n + " bytes");
          if(n == -1) {
            close();
            if(bWrtDtl) debug("CLOSED");
          } else if(n > 0){
            if(n == bfr.length) {
              synchronized(bfr) {             // The queue is OK, but need to sync for data within the buffer
                queue.add(bfr);
              }
              bfr = new byte[rdBfrSz];
            } else {
              byte[] cpy = Arrays.copyOfRange(bfr, 0, n);
              if(bWrtDtl) debug("ADDING TO Q: " + debugShowItem(-1, cpy));
              synchronized(cpy){ queue.add(cpy); }
            }
            inFlight.addAndGet(n);
            numReads.incrementAndGet();
            dataRead.addAndGet(n);

            if(bWrtDtl){
              debugShowQueue("   Thrd", 99);
            }
            delay.reset();
          } else
            delay.delay();
        }
      }
    } catch (Exception e) {
      thrd = null;
      ex = e;
      if(bThread) {
        debug("ENDING thread - Ex: " + e.toString());
        e.printStackTrace();
      }
    }
    if(bThread) debug("Thread ENDED -----");
  }
}

