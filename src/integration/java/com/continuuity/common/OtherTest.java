package com.continuuity.common;

import com.continuuity.common.conf.CConfiguration;
import org.junit.Test;

public class OtherTest {

    @Test
    public void ok() throws Exception {
      CConfiguration configuration = CConfiguration.create();
      System.out.println("This is other stdout");
    }
}
