#!/bin/sh
# wait-for-kafka.sh

set -e

host="$1"
shift
cmd="$@"

until nc -z -v -w5 ${host%:*} ${host#*:}; do
  echo "Kafka is not available - sleeping"
  sleep 2
done

echo "Kafka is up - executing command"
exec $cmd
