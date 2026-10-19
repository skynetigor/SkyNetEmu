# X11 output backpressure regression

Build with a Linux C compiler, or Android NDK Clang and run on an Android device:

```sh
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang" \
  -O2 -Wall -Wextra -Werror -pthread tools/xconnector-test/send_packet_test.c \
  -o /tmp/skynet_send_packet_test
adb push /tmp/skynet_send_packet_test /data/local/tmp/skynet_send_packet_test
adb shell /data/local/tmp/skynet_send_packet_test
adb shell rm /data/local/tmp/skynet_send_packet_test
```

The socket send buffer is deliberately small. A reader pauses for over three seconds
before draining a 1 MiB packet; the complete data must arrive in order with its
descriptor transferred exactly once. The test also interrupts a blocked poll,
checks the next packet, and verifies that shutdown and peer disconnect cancel a
blocked writer. An alarm bounds failures. Assertions must remain enabled.

The former two-second packet deadline fails the delayed-reader assertion. The
production helper is included directly so these checks exercise the same send
loop used by JNI.
