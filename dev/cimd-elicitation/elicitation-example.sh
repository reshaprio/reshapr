: "${RESHAPR_NGROK_URL:?Set RESHAPR_NGROK_URL to your public ngrok URL}"
RESHAPR_NGROK_URL="${RESHAPR_NGROK_URL%/}"

reshapr login -s http://localhost:5555 -u admin -p password

export ELICITATION_ID=`reshapr secret create-elicitation backend-elicitation --oc reshapr-proxy \
  --ocs 4JKqglcdCa0tzLCJc1rzNhdOwqw5N2De \
  --oae "${RESHAPR_NGROK_URL}/realms/backend/protocol/openid-connect/auth" \
  --ote "${RESHAPR_NGROK_URL}/realms/backend/protocol/openid-connect/token" -o json | jq -r .id`

echo "ELICITATION_ID: $ELICITATION_ID"

export SERVICE_ID=`reshapr import -u https://raw.githubusercontent.com/open-meteo/open-meteo/refs/heads/main/openapi/forecast.yml -o json | jq -r .id`

echo "SERVICE_ID: $SERVICE_ID"

export CONFIGPLAN_ID=`reshapr config create-oauth 'with-elicitation' -s $SERVICE_ID --bs $ELICITATION_ID --be https://api.open-meteo.com \
  --oas "[\"${RESHAPR_NGROK_URL}/realms/3rdparty\"]" \
  --oju "${RESHAPR_NGROK_URL}/realms/3rdparty/protocol/openid-connect/certs" \
  --osc '["openid"]' -o json | jq -r .id`

echo "CONFIGPLAN_ID: $CONFIGPLAN_ID"

reshapr expo create -n open-meteo-weather-forecast-api-1-0-with-elicitation -c $CONFIGPLAN_ID -g 1
