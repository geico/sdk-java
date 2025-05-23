package io.temporal.workflow;

import java.io.Serializable;

public final class Functions {
  private Functions() {}

  public interface TemporalFunctionalInterfaceMarker {}

  @FunctionalInterface
  public interface Func<R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply();
  }

  @FunctionalInterface
  public interface Func1<T1, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1);
  }

  @FunctionalInterface
  public interface Func2<T1, T2, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Func3<T1, T2, T3, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Func4<T1, T2, T3, T4, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Func5<T1, T2, T3, T4, T5, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Func6<T1, T2, T3, T4, T5, T6, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }

  @FunctionalInterface
  public interface Proc extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply();
  }

  @FunctionalInterface
  public interface Proc1<T1> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1);
  }

  @FunctionalInterface
  public interface Proc2<T1, T2> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Proc3<T1, T2, T3> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Proc4<T1, T2, T3, T4> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Proc5<T1, T2, T3, T4, T5>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Proc6<T1, T2, T3, T4, T5, T6>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }
}
