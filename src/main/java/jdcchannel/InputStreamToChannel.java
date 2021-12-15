package jdcchannel;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Converts an InputStream to a non-blocking ReadableByteChannel. The InputStream must already be Open.
 *
 *  An internal buffer accumulates data from the original InputStream using
 *  a separate Thread. This data is returned as requested by the 'read(ByteBuffer dst)' method.
 *
 *  NOTE: The caller should be prepared for 'read' returning 0 bytes since it does not block.
 **/
public class InputStreamToChannel implements ReadableByteChannel, Runnable {

  private InputStream strm;
  private int         rdBfrSz;
  private byte[]      bfrRead;
  private Delay       delay;

  private volatile Thread      thrd = null;
  private volatile Exception   ex = null;        // If the InputStream throws an Exception

  /** Constructor with InputStream, size to use for the internal buffer, and delay parameters */
  public InputStreamToChannel(InputStream strm, int rdBfrSz, int sleepStep, int sleepMax, boolean sleepByDoubling) {
    this.strm     = strm;
    this.rdBfrSz  = rdBfrSz;
    this.bfrRead  = new byte[rdBfrSz];
    this.delay    = new Delay(sleepStep, sleepMax, sleepByDoubling);

    thrd          = new Thread(this);
    thrd.start();
  }
  /** Constructor with InputStream and size of staging buffer */
  public InputStreamToChannel(InputStream strm, int rdBfrSz) {
    this(strm, rdBfrSz, 8, 256, true);
  }
  /** Constructor specifying only the InputStream */
  public InputStreamToChannel(InputStream strm){
    this(strm, 4096);
  }
  /** Return the original InputStream */
  public InputStream getInputStream() { return strm; }
  public boolean hadError()               { return ex != null; }
  public Exception getException()         { return ex; }

  /** Amount of data currently staged in the internal buffer */
  public int available() { return rdAvail.get(); }

  // Treat as a circular buffer.
  // rdNext updated by the Thread when it reads more data from the InputStream & writes to buffer
  // rdData updated by this read(..) method when it copies data from this buffer to ByteBuffer
  // rdAvail amount of data currently available in the intermediate buffer
  private AtomicInteger rdNext  = new AtomicInteger(0);   // Atomic so we can read these outside synchronize
  private AtomicInteger rdData  = new AtomicInteger(0);   // ... and if no room left don't synchronize at all
  private AtomicInteger rdAvail = new AtomicInteger(0);   // Amount of DATA available (otherwise data == next is ambiguous)

  // Put into 'bb' with any available data - which may be 0. Max is limit() - position()
  //          If buffer has not been wrapped:  ..... data ______ next .....   (_____ == data)
  // Wrapped, some data at end some at start:  _____ next ...... data _____   (..... == available buffer space)

  public int read(ByteBuffer bb) throws IOException {
    if(ex!=null){
      if(ex instanceof IOException ) throw (IOException) ex;
      else throw new IllegalStateException("Had Exception: " + ex.toString());
    }
    if(!isOpen()) throw new ClosedChannelException();

    int want = bb.remaining();
    if(want <= 0) return 0;   // No space left in 'bb'

    int n           = Math.min(want, rdAvail.get());

    // Read from bfrRead and put out to the ByteBuffer - return number of bytes transferred, 0 if none
    if(n <= 0)
      return 0;
    else {
      int next        = rdNext.get();
      int data        = rdData.get();
      boolean wrapped = next <= data;
      int lnthToEnd   = rdBfrSz - data;

      synchronized(rdNext) {                    // Forces memory barrier to read bfrRead contents from memory
        if (!wrapped || n <= lnthToEnd) {
          bb.put(bfrRead, data, n);
          data += n;
        } else {
          bb.put(bfrRead, data, lnthToEnd);
          data = n - lnthToEnd;
          bb.put(bfrRead, 0, data);
        }
      }
      rdData.set(data >= rdBfrSz ? 0 : data);
      rdAvail.addAndGet(-n);
      return n;
    }
  }

  public boolean isOpen() {
    return thrd!=null;
  }

  public void close() throws IOException {
    thrd = null;
  }

  public void run() {
    // Read from the InputStream into the bfrRead array
    try {
      while (thrd != null) {
        int avail = strm.available();
        if (avail > 0) {
          int data = rdData.get();
          int next = rdNext.get();
          int space= rdBfrSz - rdAvail.get();

          if( space > 0 ){
            boolean wrapped = next <= data;
            int     n       = Math.min(avail, space);

            if (wrapped) {                        // next ... data is the only space avail to write to in bfrRead
              n = strm.read(bfrRead, next, n);    // Might read less than requested
              next += n;
            } else {
              // next ... end of array then 0 ... data are possible spaces if needed
              int spaceToEnd = rdBfrSz - next;
              if (n <= spaceToEnd) {
                n = strm.read(bfrRead, next, n);
                next += n;
              } else {
                int amtRead = strm.read(bfrRead, next, spaceToEnd);
                if (amtRead < spaceToEnd) {        // read came up short
                  n     = amtRead;
                  next += amtRead;
                } else {
                  amtRead = strm.read(bfrRead, 0, n - spaceToEnd);
                  n       = spaceToEnd + amtRead;
                  next    = amtRead;
                }
              }
            }

            synchronized(bfrRead) {                   // Forces memory barrier to write bfrRead contents to memory
              delay.reset();
            }
            rdNext.set(next >= rdBfrSz ? 0 : next);
            if(n == -1){
              thrd = null;
              return;
            }
          } else
            delay.delay();
        } else
          delay.delay();
      }
    } catch (Exception e) {
      thrd = null;
      ex = e;
      return;
    }
  }
}

