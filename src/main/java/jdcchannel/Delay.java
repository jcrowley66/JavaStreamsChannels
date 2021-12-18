package jdcchannel;

/** Provide an increasing Delay of a process - thread-safe */
public class Delay {

  private static final boolean bDebug = false;
  private static final boolean bStack = bDebug & false;

  private int lastDelay = 0;
  private int delayStep;
  private int delayMax;
  private boolean delayByDoubling;

  /**
   *
   * @param delayStep     - step to increase each delay until reset. ZERO == a no-op delay
   * @param delayMax      - max delay.
   * @param delayByDouble - True  == double the delay each time from delayStep -- 8, 16, 32, ...
   *                        False == add the delay each time -- 8, 16, 24, 32, ...
   */
  public Delay(int delayStep, int delayMax, boolean delayByDouble){
    resetDelay(delayStep, delayMax, delayByDouble);
  }
  public Delay(){
    this(8, 256, true);
  }

  public int getLastDelay()           { return lastDelay; }
  public int getDelayStep()           { return delayStep; }
  public int getDelayMax()            { return delayMax; }
  public boolean getDelayByDoubling() { return delayByDoubling; }

  /** Reset to no delay, start the delay steps over */
  public void reset() {
    lastDelay = 0;
  }

  /** Reset the delay + reset all of the parameters */
  public void resetDelay(int delayStep, int delayMax, boolean delayByDouble){
    if(delayStep < 0) throw new IllegalArgumentException("delayStep must be >= 0");
    if(delayMax  < delayStep) throw new IllegalArgumentException("delayMax must be >= delayStep");

    this.delayStep        = delayStep;
    this.delayMax         = delayMax;
    this.delayByDoubling  = delayByDouble;
    lastDelay             = 0;
  }
  /** Do a delay using all of the current delay & parameter settings */
  public void delay(){
    lastDelay = delay(lastDelay, delayStep, delayMax, delayByDoubling);
  }
  /** Do a delay given all of the parameters to use. Independent of any class settings.
   * @return - the amount delayed, should be preserved as lastDelay for next call
   **/
  public int delay(int lastDelay, int sleepStep, int sleepMax, boolean byDoubling) {
    if(sleepStep==0) return 0;            // NO-OP, no delay at all
    synchronized(this) {
      int sleepFor = lastDelay >= sleepMax
                      ? sleepMax
                      : lastDelay==0
                        ? sleepStep
                        : byDoubling
                          ? (lastDelay * 2)
                          : (lastDelay + sleepStep);
      if(bDebug) {
        ln("Delay for: " + sleepFor);
        if(bStack) Thread.currentThread().dumpStack();
      }
      Delay.threadSleep(sleepFor);
      return sleepFor;
    }
  }

  /****************************************************************************************/
  /** A default delay - use IFF only have a single caller or can share the delay steps    */
  /****************************************************************************************/

  public static Delay shared = new Delay();
  public static Delay noop   = new Delay(0, 0, true);

  public static void ln(String s) { System.out.println(s); }

  public static void threadSleep(int millis) {
    try{ Thread.sleep(millis); } catch(Exception ex) { /* no-op */ }
  }
}
