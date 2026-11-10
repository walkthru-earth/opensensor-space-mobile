# opensensor.space app privacy policy (draft)

Draft for hosting at a public URL and linking from Google Play, the App Store and the app. Have
counsel review it before the first public release. Fill the placeholders in square brackets.
Keep it in step with [store-compliance.md](store-compliance.md).

Effective date [date]. Publisher [legal name], contact [privacy contact email].

## Summary

The opensensor.space app copies readings from your own air quality sensors to your phone. It has
no account, no ads, no analytics and no tracking. It never asks for your precise location. The
area you pick for a sensor is sent only to that sensor.

## What the app does with data

- **Sensor readings and files.** The app reads data files from sensors you connect over
  Bluetooth or your local Wi-Fi network and stores them on your phone. They leave your phone only
  when you choose to share or export them with your phone's share or save tools.
- **Sensor settings.** Wi-Fi names and passphrases you enter are sent only to your sensor over the
  encrypted Bluetooth link. The Wi-Fi sync token is stored encrypted on your phone. Neither is
  sent anywhere else.
- **Permissions.** Bluetooth (Nearby devices) to find and connect to your sensors, local network
  to reach them on your Wi-Fi, and notifications to show sync progress. Each is asked only when you
  use the feature. If you tap Near me on the public map, or Use phone location on a sensor's area
  map, the app asks for your approximate location, uses it on your phone to move the map, and does
  not send or store it. On the area map you then tap an area, and only that area is sent to your
  sensor when you save. On an iPhone, if you tap Scan on the sensor sticker card, the app asks for
  the camera to read the barcode on your sensor. No picture is kept.

## Public sensor map (when available)

When the public map is on, the app downloads public map data anonymously. Like any internet
request this reaches the hosts with your IP address. They are source.coop for the readings and
the map, and protomaps.github.io for the map's lettering and symbols. We do not receive access logs
and do not use it to identify you. You can turn the public map off in Settings.

With the same setting on, when a file synced from your sensor names a sensor description the app
does not have, the app downloads that description anonymously from raw.githubusercontent.com,
where the sensor software is published. It tries each description at most once each time the app
runs. A later run may try again after a failed download, or download it again if the saved copy is
gone. The request carries your IP address like any other, and its address names the sensor model,
the published software version and the description's fingerprint, so the host can tell which kind
of sensor it is for. It carries no device name, nothing about you and none of your readings. The
app keeps the description only when it matches the fingerprint the file carries. With the public
map off, nothing is downloaded.

## Sharing a sensor publicly (not available yet)

Sharing is not available in the app yet, and the app publishes nothing. When it becomes
available it will be optional and off by default. You will be able to choose to share one sensor's
readings on the public map from that sensor's settings. Only
the readings, the sensor's public name and one area of a standard map grid, an H3 resolution 8
cell, will be published. A typical cell covers about 0.74 km² with sides of about 530 m. Real cells vary
a little in size and shape. Where few people live, an area this small can point to a single home. The sensor's exact location, identifiers, Wi-Fi details, PIN and token will never be
published. Published data will be released under the Creative Commons Attribution 4.0 licence and
cannot be withdrawn. You will be able to
stop sharing at any time from the same place, and nothing will be published after the end of that
day.

## Data retention and deletion

Forget removes a sensor's enrollment and access credentials from the app, such as its saved
record, its Wi-Fi token, its Auto-sync setting and the map cell of its pin. Readings already collected from that sensor
stay on your phone until you clear the app's storage or uninstall the app. Settings can also
remove old format files and clear the History and map caches.
There is no account to delete. Published data stays public under its licence. For a legal
removal request, write to the contact below.

## Children

The app is not directed at children under 13.

## Changes

We will update this page and its effective date before the app behaves differently.

## Contact

[privacy contact email]
