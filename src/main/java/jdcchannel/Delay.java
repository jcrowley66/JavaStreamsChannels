package jdcchannel;

/** Provide an increasing Delay of a process */
public class Delay {

  private int lastDelay = 0;
  private int delayStep;
  private int delayMax;
  private boolean delayByDoubling;

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
  public void reset() { lastDelay = 0; }

  /** Reset the delay + reset all of the parameters */
  public void resetDelay(int delayStep, int delayMax, boolean delayByDouble){
    if(delayStep <= 0) throw new IllegalArgumentException("delayStep must be > 0");
    if(delayMax  <= delayStep) throw new IllegalArgumentException("delayMax must be >= delayStep");

    this.delayStep        = delayStep;
    this.delayMax         = delayMax;
    this.delayByDoubling  = delayByDouble;
    lastDelay             = 0;
  }
  /** Do a delay using all of the current delay & parameter settings */
  public void delay(){
    lastDelay = delay(lastDelay, delayStep, delayMax, delayByDoubling);
  }
  /** Do a delay given all of the parameters to use. Independent of any class settings. */
  public int delay(int lastDelay, int sleepStep, int sleepMax, boolean byDoubling) {
    if(lastDelay >= sleepMax) {
      try{ Thread.sleep(sleepMax); } catch(Exception ex) {}
      return sleepMax;
    } else {
      int sleepFor = byDoubling ? (lastDelay * 2) : (lastDelay + sleepStep);
      try{ Thread.sleep(sleepFor); } catch(Exception ex) {}
      return sleepFor;
    }
  }
}
