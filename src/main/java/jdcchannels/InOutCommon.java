package jdcchannels;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.*;

public class InOutCommon {

  protected final boolean bDebug = false;
  protected final boolean bRead  = bDebug && true;
  protected final boolean bRdDtl = bRead && false;
  protected final boolean bWrite = bDebug && true;
  protected final boolean bWrtDtl= bWrite && false;
  protected final boolean bThread= bDebug && true;

  // Active only for InputStreamToChannel
  protected AtomicInteger numReads  = new AtomicInteger(0);  // Physical reads of InputStream that completed
  protected AtomicInteger dataRead  = new AtomicInteger(0);  // Total number of bytes read from InputStream
  // Active only for OutputStreamToChannel
  protected AtomicInteger numWrites = new AtomicInteger(0);  // How many writes to OutputStream
  protected AtomicInteger dataWrtn  = new AtomicInteger(0);  // Total data sent to OutputStream

  protected AtomicInteger inFlight  = new AtomicInteger(0);   // Applies to either InputStream or OutputStream



  protected          boolean    threwAlready    = false; // Throw only once, then throw ClosedSocketException
  protected volatile Thread     thrd            = null;  // When set back to null, at EOF or Exception
  protected volatile Exception  ex              = null;  // If the Input/OutputStream throws an Exception

  protected String label                        = "";
  protected ConcurrentLinkedDeque<byte[]> queue = new ConcurrentLinkedDeque<>();

  /** Amount of data currently staged in the internal buffer
   *  NOTE: Returns -1 if EOF
   **/
  public int inFlight()   { return thrd==null ? -1 : inFlight.get(); }
  public int available()  { return inFlight(); }
  /** Number of physical reads completed on the InputStream */
  public int numReads()   { return numReads.get(); }
  /** Total data read from the InputStream */
  public int dataRead()   { return dataRead.get(); }

  /** The number of physical writes completed to the OutputStream.
   *  Note: May not match calls to write(ByteBuffer data) method since data may be accumulated into a single write.
   */
  public int numWrites()   { return numWrites.get(); }
  /** Total number of bytes sent to the OutputStream */
  public int dataSent()    { return dataWrtn.get(); }

  protected void throwIfEx() throws IOException {
    if(ex!=null && !threwAlready){
      threwAlready = true;
      if(ex instanceof IOException) throw (IOException) ex;
      else throw new IllegalStateException("Had Exception: " + ex.toString());
    }
  }
  protected static void ln(String s) { System.out.println(s); }

  protected void debug(String s) {
    if(bDebug) {
      ln("DEBUG -- " + label + ": " + s);
    }
  }

  protected final int     nItemSz= 16;      // max data to show
  
  protected String debugShowItem(int index, byte[] data){
    String s  = "";
    if(bDebug){
      int    sz = data.length < nItemSz ? data.length : nItemSz;
      String mk = data.length > nItemSz ? "..." : "";
      for(int i=0; i<sz; i++){
        byte b = data[i];
        String sdata = "" + b;
        if(32 <= b && b <= 126) sdata = String.valueOf((char) data[i]);
        s += sdata + ",";
        
      }
      s = "  QItem -- " + index + "  " + s + mk;
    }
    return s;
  }

  protected void debugShowQueue(String labelIn, int max){
    if(bDebug){
      int sz = queue.size();
      debug(labelIn + " -- Queue Size: " + sz + ", ID: " + System.identityHashCode(queue) + " This: " + System.identityHashCode(this));
      Object[] obj = queue.toArray();
      if(obj.length==0)
        debug(labelIn + " -- toArray -- Queue is EMPTY");
      else for(int i=0; i<obj.length; i++){
        debug(labelIn + " -- ToArray -- " + debugShowItem(i, (byte[])obj[i]));
      }
    }
  }
}
