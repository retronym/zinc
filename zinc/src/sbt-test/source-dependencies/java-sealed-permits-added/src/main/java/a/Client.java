package a;

public class Client {
  public static int f(S s) {
    return switch (s) {
      case A x -> 1;
    };
  }
}
