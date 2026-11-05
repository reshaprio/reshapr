---
name: open-meteo-forecast
description: Get a weather forecast for a city using the Open-Meteo get_v1_forecast tool and summarize it.
license: Apache-2.0
---

# Open-Meteo forecast

1. Resolve the latitude and longitude of the requested location.
2. Call `get_v1_forecast` with `latitude`, `longitude` and the variables listed in
   [references/VARIABLES.md](references/VARIABLES.md).
3. Summarize the result in two sentences with temperatures in °C.
