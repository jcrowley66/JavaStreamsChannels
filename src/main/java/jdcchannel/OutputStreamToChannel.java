package jdcchannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class OutputStreamToChannel implements WritableByteChannel, Runnable {

  private OutputStream  strm;
  private int           wrtBfrSz;
  private byte[]        bfrWrite;
  private Delay         delay;

  private volatile Thread     thrd;
  private volatile Exception  ex = null;        // If the OutputStream throws an Exception

  /** Constructor with OutputStream, size to use for the internal buffer, and delay parameters */
  public OutputStreamToChannel(OutputStream strm, int wrtBfrSz, int sleepStep, int sleepMax, boolean sleepByDoubling) {
    this.strm       = strm;
    this.wrtBfrSz   = wrtBfrSz;
    this.bfrWrite   = new byte[wrtBfrSz];
    this.delay      = new Delay(sleepStep, sleepMax, sleepByDoubling);

    wrtSpace = new AtomicInteger(wrtBfrSz);

    thrd            = new Thread(this);
    thrd.start();
  }
  /** Constructor with OutputStream and size of staging buffer */
  public OutputStreamToChannel(OutputStream strm, int rdBfrSz) {
    this(strm, rdBfrSz, 8, 256, true);
  }
  /** Constructor specifying only the OutputStream */
  public OutputStreamToChannel(OutputStream strm){
    this(strm, 4096);
  }

  public OutputStream getOutputStream()   { return strm; }
  public boolean isOpen()                 { return thrd != null; }
  public void close() throws IOException  { thrd = null; }
  public boolean hadError()               { return ex != null; }
  public Exception getException()         { return ex; }

  private AtomicInteger wrtNext  = new AtomicInteger(0);   // Atomic so we can read these outside synchronize
  private AtomicInteger wrtData  = new AtomicInteger(0);   // ... and if no room left don't synchronize at all
  private AtomicInteger wrtSpace;                                    // Amount of SPACE available in the buffer


  //          If buffer has not been wrapped:  ..... data ______ next .....   (_____ == data)
  // Wrapped, some data at end some at start:  _____ next ...... data _____   (..... == available buffer space)
  /** Write from the src ByteBuffer to the socket, return number of bytes actually tranferred
   *  Actually, writes from 'src' into our internal buffer, then the write Thread writes to the socket
   **/
  public int write(ByteBuffer src) throws IOException {
    if(ex!=null){
      if(ex instanceof IOException ) throw (IOException) ex;
      else throw new IllegalStateException("Had Exception: " + ex.toString());
    }
    if(!isOpen()) throw new ClosedChannelException();

    int want = src.remaining();
    if(want <= 0) return 0;

    int availSpace  = wrtSpace.get();
    int n           = Math.min(want, availSpace);

    // Get from src ByteBuffer and write into our internal bfrWrite
    if(n <= 0)
      return 0;
    else {
      int next        = wrtNext.get();
      int data        = wrtData.get();
      boolean wrapped = next <= data;
      int spaceToEnd  = wrtBfrSz - next;

      if(wrapped || n <= spaceToEnd){
        src.get(bfrWrite, next, n);       // Reads from 'src' and puts into bfrWrite
        next += n;
      } else {
        src.get(bfrWrite, next, spaceToEnd);
        src.get(bfrWrite, 0, n - spaceToEnd);
        next = n - spaceToEnd;
      }
      synchronized(wrtNext) {               // Forces memory barrier for bfrWrite
        wrtNext.set( next >= wrtBfrSz ? 0 : next);
        wrtSpace.addAndGet(-n);
      }
      return n;
    }
  }

  // Write from the buffer to the OutputStream.
  public void run() {
    while(thrd != null) try {
      int availData = wrtBfrSz - wrtSpace.get();
      if(availData > 0){
        int next;
        int data;

        synchronized(wrtNext){              // Clears cache of this CPU so we read wrtBuffer from memory
          next = wrtNext.get();
          data = wrtData.get();
        }
        if(next <= data){
          int dataToEnd = wrtBfrSz - data;
          if(dataToEnd > 0){
            strm.write(bfrWrite, data, dataToEnd);
            wrtSpace.addAndGet(dataToEnd);
            data = 0;
          }
          if(next > 0){
            strm.write(bfrWrite, 0, next);
            wrtSpace.addAndGet(next);
            data = next;
          }
        } else {        // Not wrapped ..... data ______ next .....   (_____ == data)
          strm.write(bfrWrite, data, next - data);
          wrtSpace.addAndGet(next - data);
          data = next;
        }
        wrtData.set(data >= wrtBfrSz ? 0 : data);
        delay.reset();
      } else
        delay.delay();
    } catch(Exception e){
      ex = e;
      thrd = null;
      return;
    }
  }
}
