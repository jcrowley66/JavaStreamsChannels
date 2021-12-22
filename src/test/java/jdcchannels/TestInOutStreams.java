package jdcchannels;

import java.io.*;
import java.nio.*;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.*;

/** Fake some InputStreams and OutputStreams and pass it through InputStreamToChannel and OutputStreamToChannel */
public class TestInOutStreams {

  private static final boolean bDebug = false;

  public static void main(String[] args){

    TestInOutStreams tst = new TestInOutStreams();
    tst.initTest1();
    tst.runTest1(new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024}, -1);
    tst.runTest1(new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024}, 512);
    tst.runTest2("        Fibonacci, no Delay", new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024}, Delay.noop);
    tst.runTest2("  Fibonacci, standard Delay", new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024, 37}, new Delay());
    tst.runTest2(" Fibonacci+, standard Delay", new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024, 37, 1, 2,  3, 5, 8}, new Delay());
    tst.runTest2("Fibonacci++, standard Delay", new int[]{ 1, 2, 3, 5, 8, 13, 21, 1024, 37, 1, 2,  0, 3, 5, 8, 13, 21, 931}, new Delay());
    System.exit(0);
  }

  private boolean runTest1(int[] blockSizes, int EOFAfter){
    boolean    rslt = true;
    ByteBuffer bb;
    int        index = 0;
    int        n;
    Delay      delay = new Delay();
    boolean    EOF = false;

    ReadableByteChannel chnl = new InputStreamToChannel("Test1 InStrm", new FakeIn(test1Data, EOFAfter), 31, 1024);
    for(int i=0; i<blockSizes.length; i++) {
      int sz = blockSizes[i];
      if (EOF)
        ln("Block: " + i + " Size: " + sz + " -- skipping, at EOF");
      else{
        bb = ByteBuffer.allocate(sz);
        try {
          n = 0;
          while (bb.remaining() > 0 && !EOF) {
            int nNow = chnl.read(bb);
            n += nNow;
            if (nNow== -1) {
              EOF = true;
              debug("Got EOF");
            } else if (n == 0)
              delay.delay();
            else
              delay.reset();
          }
        } catch (IOException ex) {
          ln("Exception -- " + ex.toString());
          n = Integer.MIN_VALUE;
        }

        if (n == Integer.MIN_VALUE)
          rslt = false;
        else if(EOF){
          // NO-OP
        } else if (n == sz) {
          debug("Read block " + i + " Size: " + sz);
          for (int j = 0; j < sz; j++) {
            if (bb.get(j) != test1Data[index + j]) {
              ln("Mismatch reading block " + i + " Expected: " + test1Data[index + j] + ", Got: " + bb.get(j));
              rslt = false;
            }
          }
          index += n;
        } else {
          rslt = false;
          ln("Expected " + sz + " bytes, got " + n);
        }
      }
    }
    ln("Test1 " + rslt);
    return rslt;
  }

  /** Write the various blocks to the output channel */
  private boolean runTest2(String label, int[] blockSizes, Delay delay) {
    boolean rslt  = true;
    int     index = 0;
    ByteBuffer bb = ByteBuffer.allocate(4096 * 8);

    FakeOut out = new FakeOut(delay);

    WritableByteChannel chnl = new OutputStreamToChannel("Test2", out);

    int amtWritten = 0;

    for(int i=0; i<blockSizes.length; i++){
      int sz = blockSizes[i];
      bb.position(0);
      bb.limit(sz);
      bb.put(test1Data, amtWritten, sz);
      bb.position(0);
      bb.limit(sz);
      try{
        int n = 0;
        while(n<sz){
          n += chnl.write(bb);
        }
        amtWritten += n;
      } catch(Exception ex){
        ln("Exception: " + ex.toString());
        return false;
      }
    }

    try {
      out.close();
    } catch(Exception ex){
      ln("Test2 exception -- Ex: " + ex.toString());
    }
    rslt = out.dataMatches(test1Data, amtWritten);

    ln("Test2 -- " + label + ": " + rslt + " NumBlocks: " + blockSizes.length + " TotalData: " + amtWritten);
    return rslt;
  }
  /******************************************************************************************/
  /** FAKE InputStream - instantiate with array of test data to be returned                 */
  /******************************************************************************************/
  class FakeIn extends InputStream {

    private byte[]  data;
    private int     index = 0;
    private int     EOFAfter = -1;        // If -1 never issue an EOF, just cycle
    private boolean atEOF = false;

    public FakeIn(byte[] data, int EOFAfter){
      this.data     = data;
      this.EOFAfter = EOFAfter;
    }
    public int available() {
      if(EOFAfter == -1)
        return data.length - index;
      else if(EOFAfter <= index) {
        atEOF = true;
        debug("FakeIn Available ZERO -- Index: " + index + " Length: " + data.length + " EOFAfter: " + EOFAfter);
        return 0;
      } else
        return EOFAfter - index;
    }
    /** Read a single byte */
    public int read() throws IOException {
      if(EOFAfter > -1 && index >= EOFAfter) return -1;
      else {
        if(index >= data.length) index = 0;
        return data[index++];
      }
    }
    @Override public int read(byte[] array){
      int avail = available();
      if(atEOF) return -1;

      debug("FakeIn Test read(array) -- Avail: " + avail);
      int amt   = Math.min(avail, array.length);
      if(amt > 0){
        byte[] out = Arrays.copyOfRange(data, index, index + amt );
        for(int i=0; i<amt; i++) array[i] = out[i];
        index += amt;
      }
      return amt;
    }
    @Override public int read(byte[] array, int from, int lnth){
      int avail = available();
      if(atEOF) return -1;

      debug("FakeIn Test read(array, from, lnth) -- Avail: " + avail);
      int amt   = Math.min(avail, lnth);
      if(amt > 0){
        byte[] out = Arrays.copyOfRange(data, index, index + amt );
        for(int i=0; i<amt; i++) array[from + i] = out[i];
        index += amt;
      }
      return amt;
    }
  }

  /******************************************************************************************/
  /** FAKE OutputStream - writes all data to a public data array                            */
  /******************************************************************************************/
  class FakeOut extends OutputStream {

    private Object lock1 = new Object();
    private Object lock2 = new Object();
    private Delay  delay;
    public  byte[] data;
    public  int    index= 0;

    public FakeOut(Delay delay, int szDataBfr){
      this.delay = delay;
      data = new byte[szDataBfr];
    }
    public FakeOut(Delay delay){
      this(delay, 4096 * 8);
    }

    public boolean dataMatches(byte[] test, int amt){
      boolean rslt = true;
      synchronized(lock1){
        while(index < amt)
          Delay.threadSleep(100);        // Wait for thread to write out all the data
      }
      for(int i=0; i<amt; i++) {
        if(test[i]!=data[i]){
          ln("FAIL -- Index: " + i + ", Test[i]: " + test[i] + ", Data[i]: " + data[i]);
          rslt = false;
        }
      }
      return rslt;
    }

    public void write(int b) throws IOException {
      delay.delay();
      synchronized(lock2) {
        data[index++] = (byte)b;
      }
    }

    public void write(byte[] b, int off, int len) throws IOException {
      delay.delay();
      synchronized(lock2) {
        for(int i=0; i<len; i++){
          data[index++] = b[i];
        }
      }
    }
  }

  private static void initTest1(){
    for(int i = 0; i< test1Data.length; i++) test1Data[i] = (byte)(i & 0xFF);
  }

  private static byte[] test1Data = new byte[8192];

  private static void ln(String s) { System.out.println(s); }

  private static void debug(String s) { if(bDebug) ln("DEBUG: " + s); }
}
