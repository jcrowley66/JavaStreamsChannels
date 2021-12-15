package jdcchannel;

public class Common {

  public static  int delay(int lastDelay, int sleepStep, int sleepMax) {
    if(lastDelay >= sleepMax) {
      try{ Thread.sleep(sleepMax); } catch(Exception ex) {}
      return sleepMax;
    } else {
      int sleepFor = lastDelay += sleepStep;
      try{ Thread.sleep(sleepFor); } catch(Exception ex) {}
      return sleepFor;
    }
  }

}
