# Touchpad under the keys (kb_touchpad). The kernel reports it as a 1440x720 touchscreen; disabled
# here so key touches do not reach the screen. InputReader does not read it (support for
# device.disabled is a frameworks/native patch from lindroid-a16), swipes are handled by
# Titan2Parts (TouchpadController).
device.disabled = 1
