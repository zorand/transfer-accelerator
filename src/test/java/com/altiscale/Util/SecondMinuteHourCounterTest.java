/**
 * Copyright 2014 Altiscale <cosmin@altiscale.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

/* SecondMinuteHourCounter unittest. */
package com.altiscale.Util;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

class TestTimer extends AltiTimer {
   /** Fake timer class useful in testing */
   long time;

   public TestTimer(long time) {
     this.time = time;
   }
   public void setTime(long time) {
     this.time = time;
   }

   @Override
   public long currentTimeMillis() {
     return time;
   }
}

public class SecondMinuteHourCounterTest extends TestCase {

  /**
   * Create the test case
   *
   * @param testName name of the test case
   */
  public SecondMinuteHourCounterTest(String testName) {
    super(testName);
  }

  /**
   * @return the suite of tests being tested
   */
  public static Test suite() {
    return new TestSuite(SecondMinuteHourCounterTest.class);
  }

  public void testExpireAfterOneSecond() {
    TestTimer timer = new TestTimer(0);
    SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "Test Counter", 1000L);

    counter.increment();

    timer.setTime(999);
    assert counter.getLastSecondCnt() == 1;

    timer.setTime(1000);
    assert counter.getLastSecondCnt() == 1;

    timer.setTime(1001);
    assert counter.getLastSecondCnt() == 0;
  }

  public void testExpireAfterOneMinute() {
    TestTimer timer = new TestTimer(0);
    SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "Test Counter", 1000L);

    counter.increment();

    timer.setTime(60 * 1000 - 1);
    assert counter.getLastMinuteCnt() == 1;

    timer.setTime(60 * 1000);
    assert counter.getLastMinuteCnt() == 1;

    timer.setTime(60 * 1000 + 1);
    assert counter.getLastMinuteCnt() == 0;
  }

  public void testExpireAfterOneHour() {
    TestTimer timer = new TestTimer(0);
    SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "TestCounter", 1000L);

    counter.increment();

    timer.setTime(60 * 60 * 1000 - 1);
    assert counter.getLastHourCnt() == 1;

    timer.setTime(60 * 60 * 1000);
    assert counter.getLastHourCnt() == 1;

    timer.setTime(60 * 60 * 1000 + 1);
    assert counter.getLastHourCnt() == 0;
  }

  public void testThreeIncrements() {
    /**
       Simple test where increment is used 3 time.
       time     0.001s - add 1
       time     0.500s - add 100
       time    60.000s - add 10000
     */
    TestTimer timer = new TestTimer(0);
    SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "Test Counter", 1000L);

    // first increment
    timer.setTime(1);
    counter.increment();

    // second increment
    timer.setTime(500);
    counter.incrementBy(100);

    // the two increments are in our 1s window
    timer.setTime(1000);
    assert counter.getLastSecondCnt() == 101;

    // first increment is out of the 1s window
    timer.setTime(1500);
    assert counter.getLastSecondCnt() == 100;

    // second increment is out of the 1s window
    timer.setTime(1501);
    assert counter.getLastSecondCnt() == 0;

    // third increment
    timer.setTime(60 * 1000);
    counter.incrementBy(10000);

    // all three increments are in the 1min window.
    assert counter.getLastMinuteCnt() == 10101;

    // first increment out of the 1min window
    timer.setTime(60 * 1000 + 1);
    assert counter.getLastMinuteCnt() == 10100;

    // all increments in the 1h window.
    timer.setTime(60 * 60 * 1000);
    assert counter.getLastHourCnt() == 10101;

    // first two increments out of the 1h window.
    timer.setTime(60 * 60 * 1000 + 4000);
    assert counter.getLastHourCnt() == 10000;

    // all increments out of the 1h window.
    timer.setTime(2 * 60 * 60 * 1000);
    assert counter.getLastHourCnt() == 0;
  }

  public void testOneBucket() {
    TestTimer timer = new TestTimer(0);
    SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "One bucket", 1L);

    counter.increment();

    timer.setTime(999);
    assert counter.getLastSecondCnt() == 1;
    timer.setTime(1000);
    assert counter.getLastSecondCnt() == 1;
    timer.setTime(1001);
    assert counter.getLastSecondCnt() == 0;

    timer.setTime(60 * 1000 - 1);
    assert counter.getLastMinuteCnt() == 1;
    timer.setTime(60 * 1000);
    assert counter.getLastMinuteCnt() == 1;
    timer.setTime(60 * 1000 + 1);
    assert counter.getLastMinuteCnt() == 0;
  }

  public void testFourBuckets() {
     TestTimer timer = new TestTimer(0);
     SecondMinuteHourCounter counter = new SecondMinuteHourCounter(timer, "Four buckets", 4L);

     counter.increment();
     timer.setTime(249);
     counter.increment();

     // first two increments are fall into the [0..250) bucket
     timer.setTime(1000);
     assert counter.getLastSecondCnt() == 2;
     timer.setTime(1001);
     assert counter.getLastSecondCnt() == 0;
  }
}
