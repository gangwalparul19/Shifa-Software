package com.shifa.oms.gst.filing.domain;

/**
 * Pure validator of the GST filing-status lifecycle (GST returns &amp; filing, Reqs 1.2, 1.3, 1.4,
 * 1.7, 1.8, 2.3, 2.7).
 *
 * <p>Each method takes the <em>current</em> {@link FilingStatus} and returns a {@link FilingTransition}
 * describing whether the requested move is legal. Accepted transitions carry the new status; rejected
 * transitions carry a {@link FilingTransition.Reason} and leave the current status unchanged. No
 * exceptions are thrown and no state is held, so the machine is total and trivially property-testable.
 *
 * <p>The legal lifecycle is:
 * <pre>
 *   NOT_STARTED --prepare--&gt; PREPARED --file--&gt; FILED
 *        ^                                          |
 *        |                                          |
 *        +----------------- reopen -----------------+  (FILED --reopen--&gt; PREPARED)
 * </pre>
 *
 * <p>Pure and Spring-free; no JPA.
 */
public final class FilingStatusMachine {

    private FilingStatusMachine() {
    }

    /**
     * Attempt to mark a return as prepared.
     *
     * @param current the current filing status
     * @return {@link FilingTransition#accept(FilingStatus) accept(PREPARED)} iff {@code current} is
     *         {@link FilingStatus#NOT_STARTED}; otherwise
     *         {@link FilingTransition.Reason#INVALID_TRANSITION} (Reqs 1.3, 1.7)
     */
    public static FilingTransition prepare(FilingStatus current) {
        if (current == FilingStatus.NOT_STARTED) {
            return FilingTransition.accept(FilingStatus.PREPARED);
        }
        return FilingTransition.reject(FilingTransition.Reason.INVALID_TRANSITION);
    }

    /**
     * Attempt to file a prepared return.
     *
     * @param current the current filing status
     * @return {@link FilingTransition#accept(FilingStatus) accept(FILED)} iff {@code current} is
     *         {@link FilingStatus#PREPARED}; {@link FilingTransition.Reason#PERIOD_LOCKED} when the
     *         return is already {@link FilingStatus#FILED} (its period is locked, Reqs 1.8, 2.3);
     *         otherwise {@link FilingTransition.Reason#INVALID_TRANSITION} (Req 1.4)
     */
    public static FilingTransition file(FilingStatus current) {
        if (current == FilingStatus.PREPARED) {
            return FilingTransition.accept(FilingStatus.FILED);
        }
        if (current == FilingStatus.FILED) {
            return FilingTransition.reject(FilingTransition.Reason.PERIOD_LOCKED);
        }
        return FilingTransition.reject(FilingTransition.Reason.INVALID_TRANSITION);
    }

    /**
     * Attempt to reopen a filed return back to prepared.
     *
     * @param current the current filing status
     * @return {@link FilingTransition#accept(FilingStatus) accept(PREPARED)} iff {@code current} is
     *         {@link FilingStatus#FILED}; otherwise {@link FilingTransition.Reason#NOT_FILED}, since
     *         only a filed return can be reopened (Reqs 2.4, 2.7)
     */
    public static FilingTransition reopen(FilingStatus current) {
        if (current == FilingStatus.FILED) {
            return FilingTransition.accept(FilingStatus.PREPARED);
        }
        return FilingTransition.reject(FilingTransition.Reason.NOT_FILED);
    }
}
