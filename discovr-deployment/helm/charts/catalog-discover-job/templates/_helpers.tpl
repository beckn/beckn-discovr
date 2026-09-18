{{/*
Expand the name of the chart.
*/}}
{{- define "beckn-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name — uses release name.
*/}}
{{- define "beckn-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label.
*/}}
{{- define "beckn-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "beckn-service.labels" -}}
helm.sh/chart: {{ include "beckn-service.chart" . }}
{{ include "beckn-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "beckn-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "beckn-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Service account name.
*/}}
{{- define "beckn-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "beckn-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Full image name — supports registry, digest, and tag.
*/}}
{{- define "beckn-service.image" -}}
{{- $registry := .Values.image.registry | default .Values.global.image.registry | default "" -}}
{{- $repository := .Values.image.repository -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- $digest := .Values.image.digest | default "" -}}
{{- if $digest -}}
  {{- if $registry -}}
    {{- printf "%s/%s@%s" $registry $repository $digest -}}
  {{- else -}}
    {{- printf "%s@%s" $repository $digest -}}
  {{- end -}}
{{- else -}}
  {{- if $registry -}}
    {{- printf "%s/%s:%s" $registry $repository $tag -}}
  {{- else -}}
    {{- printf "%s:%s" $repository $tag -}}
  {{- end -}}
{{- end -}}
{{- end }}
