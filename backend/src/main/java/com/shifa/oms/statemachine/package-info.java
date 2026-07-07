/**
 * Order status state machine.
 *
 * <p>Validates every lifecycle transition against an explicit transition table;
 * illegal transitions are rejected (HTTP 409) and the current status is retained.
 */
package com.shifa.oms.statemachine;
