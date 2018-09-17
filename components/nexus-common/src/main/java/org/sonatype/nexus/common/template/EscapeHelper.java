/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.common.template;

import org.sonatype.nexus.common.encoding.EncodingUtil;

import org.apache.commons.lang.StringEscapeUtils;

/**
 * Helper to escape values.
 *
 * @since 3.0
 */
@TemplateAccessible
public class EscapeHelper
{
  public String html(final String value) {
    return StringEscapeUtils.escapeHtml(value);
  }

  public String html(final Object value) {
    return html(String.valueOf(value));
  }

  public String url(final String value) {
    if (value == null) {
      return null;
    }
    else {
      return EncodingUtil.urlEncode(value);
    }
  }

  public String url(final Object value) {
    return url(String.valueOf(value));
  }

  public String xml(final String value) {
    return StringEscapeUtils.escapeXml(value);
  }

  public String xml(final Object value) {
    return xml(String.valueOf(value));
  }

  public String uri(final String value) {
    if (value == null) {
      return null;
    }
    else {
      return url(value)
          .replaceAll("\\+", "%20")
          .replaceAll("\\%21", "!")
          .replaceAll("\\%27", "'")
          .replaceAll("\\%28", "(")
          .replaceAll("\\%29", ")")
          .replaceAll("\\%7E", "~");
    }
  }

  public String uri(final Object value) {
    return uri(String.valueOf(value));
  }

  /**
   * Strip java el start token from a string
   * @since 3.next
   */
  public String stripJavaEl(final String value) {
    if (value != null) {
      return value.replaceAll("\\$+\\{", "{");
    }
    return null;
  }
}
