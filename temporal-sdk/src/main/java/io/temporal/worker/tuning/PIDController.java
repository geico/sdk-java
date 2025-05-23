package io.temporal.worker.tuning;

/**
 * A simple PID closed control loop. <br>
 * <br>
 * License : MIT
 *
 * @author Charles Grassin
 */
class PIDController {
  // PID coefficients
  private double setPoint;
  private double kP, kI, kD;

  /** Limit bound of the output. */
  private double minLimit = Double.NaN, maxLimit = Double.NaN;

  // Dynamic variables
  private double previousTime = Double.NaN;
  private double lastError = 0;
  private double integralError = 0;

  /**
   * Constructs a new PID with set coefficients.
   *
   * @param setPoint The initial target value.
   * @param kP The proportional gain coefficient.
   * @param kI The integral gain coefficient.
   * @param kD The derivative gain coefficient.
   */
  PIDController(final double setPoint, final double kP, final double kI, final double kD) {
    this.setSetpoint(setPoint);
    this.kP = kP;
    this.kI = kI;
    this.kD = kD;
  }

  /**
   * Updates the controller with the current time and value and outputs the PID controller output.
   *
   * @param currentTime The current time (in arbitrary time unit, such as seconds). If the PID is
   *     assumed to run at a constant frequency, you can simply put '1'.
   * @param currentValue The current, measured value.
   * @return The PID controller output.
   */
  double getOutput(final double currentTime, final double currentValue) {
    final double error = setPoint - currentValue;
    final double dt = (!Double.isNaN(previousTime)) ? (currentTime - previousTime) : 0;

    // Compute Integral & Derivative error
    final double derivativeError = (dt != 0) ? ((error - lastError) / dt) : 0;
    integralError += error * dt;

    // Save history
    previousTime = currentTime;
    lastError = error;

    return checkLimits((kP * error) + (kI * integralError) + (kD * derivativeError));
  }

  /** Resets the integral and derivative errors. */
  void reset() {
    previousTime = 0;
    lastError = 0;
    integralError = 0;
  }

  /**
   * Bounds the PID output between the lower limit and the upper limit.
   *
   * @param output The target output value.
   * @return The output value, bounded to the limits.
   */
  private double checkLimits(final double output) {
    if (!Double.isNaN(minLimit) && output < minLimit) return minLimit;
    else if (!Double.isNaN(maxLimit) && output > maxLimit) return maxLimit;
    else return output;
  }

  // Getters & Setters

  /**
   * Sets the output limits of the PID controller. If the minLimit is superior to the maxLimit, it
   * will use the smallest as the minLimit.
   *
   * @param minLimit The lower limit of the PID output.
   * @param maxLimit The upper limit of the PID output.
   */
  void setOuputLimits(final double minLimit, final double maxLimit) {
    if (minLimit < maxLimit) {
      this.minLimit = minLimit;
      this.maxLimit = maxLimit;
    } else {
      this.minLimit = maxLimit;
      this.maxLimit = minLimit;
    }
  }

  /** Removes the output limits of the PID controller */
  void removeOuputLimits() {
    this.minLimit = Double.NaN;
    this.maxLimit = Double.NaN;
  }

  /**
   * @return the kP parameter
   */
  public double getkP() {
    return kP;
  }

  /**
   * @param kP the kP parameter to set
   */
  void setkP(double kP) {
    this.kP = kP;
    reset();
  }

  /**
   * @return the kI parameter
   */
  double getkI() {
    return kI;
  }

  /**
   * @param kI the kI parameter to set
   */
  void setkI(double kI) {
    this.kI = kI;
    reset();
  }

  /**
   * @return the kD parameter
   */
  double getkD() {
    return kD;
  }

  /**
   * @param kD the kD parameter to set
   */
  void setkD(double kD) {
    this.kD = kD;
    reset();
  }

  /**
   * @return the setPoint
   */
  double getSetPoint() {
    return setPoint;
  }

  /**
   * Establishes a new set point for the PID controller.
   *
   * @param setPoint The new target point.
   */
  void setSetpoint(final double setPoint) {
    reset();
    this.setPoint = setPoint;
  }
}
