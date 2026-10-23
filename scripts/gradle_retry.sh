#!/usr/bin/env bash
set -u

task="${1:?usage: gradle_retry.sh <gradle-task> [gradle-args...]}"
shift
gradle_args=("$@")
attempts="${GRADLE_RETRY_ATTEMPTS:-4}"
delay="${GRADLE_RETRY_DELAY:-45}"

for attempt in $(seq 1 "$attempts"); do
  echo "::group::./gradlew ${gradle_args[*]} $task (attempt $attempt/$attempts)"
  ./gradlew "${gradle_args[@]}" "$task"
  status=$?
  echo "::endgroup::"

  if [ "$status" -eq 0 ]; then
    exit 0
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    echo "::warning::./gradlew $task failed with exit $status; retrying in ${delay}s"
    sleep "$delay"
  fi
done

echo "::error::./gradlew $task failed after $attempts attempts"
exit "$status"
