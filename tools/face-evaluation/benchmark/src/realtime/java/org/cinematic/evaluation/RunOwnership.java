package org.cinematic.evaluation;

/** Process-wide admission, including detector drain and report persistence across recreation. */
final class RunOwnership {
    private Object owner;
    synchronized boolean idle() { return owner==null; }
    synchronized boolean acquire(Object candidate) {
        if(candidate==null) throw new IllegalArgumentException("owner");
        if(owner!=null) return false;
        owner=candidate; return true;
    }
    synchronized void release(Object candidate) {
        if(owner!=candidate || candidate==null) throw new IllegalStateException("owner");
        owner=null;
    }
}
