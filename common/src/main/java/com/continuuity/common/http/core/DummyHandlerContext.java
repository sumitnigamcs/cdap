/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */
package com.continuuity.common.http.core;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * DummyHandlerContext returns an empty runtime arguments and null resource handler.
 */
public class DummyHandlerContext implements HandlerContext {

  @Override
  public Map<String, String> getRunTimeArguments() {
   return ImmutableMap.of();
  }

  @Override
  public HttpResourceHandler getHttpResourceHandler() {
    return null;
  }
}
