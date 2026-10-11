package a;

public interface F1 {
  int apply();
  default int other() { return 0; }
}
