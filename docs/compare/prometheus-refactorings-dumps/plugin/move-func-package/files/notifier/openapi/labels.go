package openapi

import (
	"github.com/prometheus/alertmanager/api/v2/models"

	"github.com/prometheus/prometheus/model/labels"
)

func labelsToOpenAPILabelSet(modelLabelSet labels.Labels) models.LabelSet {
	apiLabelSet := models.LabelSet{}
	modelLabelSet.Range(func(label labels.Label) {
		apiLabelSet[label.Name] = label.Value
	})

	return apiLabelSet
}
