---
title: Tutorials
description: codeps Tutorials
pagination:
  enabled: false
---

# Learn codeps

Follow these in order. They use the same small loop: record overall health,
identify the package worth changing, then narrow the investigation to files.
Input setup for Scala and Java lives in [How-Tos](/howtos).

{% for tut in site.data.project.tutorials %}- [{{ tut.label }}]({{ tut.url }})
{% endfor %}
