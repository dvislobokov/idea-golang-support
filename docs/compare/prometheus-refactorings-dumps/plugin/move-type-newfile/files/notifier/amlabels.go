package notifier

import (
	"net/url"

	"github.com/prometheus/common/model"

	"github.com/prometheus/prometheus/model/labels"
)

type alertmanagerLabels struct{ labels.Labels }

func (a alertmanagerLabels) url() *url.URL {
	return &url.URL{
		Scheme: a.Get(model.SchemeLabel),
		Host:   a.Get(model.AddressLabel),
		Path:   a.Get(pathLabel),
	}
}
