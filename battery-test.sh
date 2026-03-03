#!/bin/bash

while true; do
  echo "For new testing cycle, press enter, then activate the FAB"
  echo "To quit, hit CTRL+C"

  # Wait for input with adb keepalive.
  while true; do
    adb shell echo . >/dev/null

    # Read, with 60s timeout
    read -t 60 -r x
    if [ $? -eq 0 ]; then
      # Quit the loop on successful read
      break
    fi
  done

  # Reset battery stats.
  date
  adb shell dumpsys batterystats --reset

  # Sleep ~30 minutes with adb keepalive.
  for i in $(seq 1 30); do
    adb shell echo . >/dev/null
    sleep 1m
  done

  date
  file="trial-$(date -Iminutes).txt"
  adb shell dumpsys batterystats > "$file"
  echo "Test complete, full stats in $file"
  grep -E "Computed drain|Total run time" "$file"

  echo; echo
done
