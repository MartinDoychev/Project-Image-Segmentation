package com.project.image.segmentation.exceptions;

/** Domain-specific exception for processing errors. */
public class SegmentationException extends RuntimeException {
    public SegmentationException(String message) { super(message); }
    public SegmentationException(String message, Throwable cause) { super(message, cause); }
}
