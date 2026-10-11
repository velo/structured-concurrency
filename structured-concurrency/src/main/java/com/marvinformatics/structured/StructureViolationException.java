package com.marvinformatics.structured;

import java.io.Serial;

/** Thrown when a structure violation is detected, mirroring the JDK exception of the same name. */
public final class StructureViolationException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = -7705327650798235468L;

  /** Creates the exception with no detail message. */
  public StructureViolationException() {}

  /**
   * Creates the exception with a detail message.
   *
   * @param message the detail message
   */
  public StructureViolationException(String message) {
    super(message);
  }
}
