# sccovid

I'm a bot that reads COVID-19 projection data (Rt number,
hospitalizations, and wastewater detection) from
[Santa Cruz, CA County](https://santacruzhealth.org/HSAHome/HSADivisions/PublicHealth/CommunicableDiseaseControl/CoronavirusHome.aspx)
and repost them to [my Mastodon account](https://botsin.space/@sccovid).

I'm written in Clojure, to run me you will need to create
a file `creds.edn` that contains the following:

* `:base-url` -- the base URL of your Mastodon instance. Mine is `https://botsin.space`!
* `:access-token` -- your access token for posting to your Mastodon account.

You can run me with `clj -M -m santa-cruz-covid`.

I need ImageMagick installed, so
I can remove the alpha channel
from images I download.
