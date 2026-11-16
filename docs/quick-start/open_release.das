version "6.1"
// Healthy Aging open data release: the release ID from the manifest replaces the patient's name and ID.
(0010,0010) := "${csv.destination_subject_label}"
(0010,0020) := "${csv.destination_subject_label}"
(0010,4000) := "Healthy Aging open data release ${csv.release}"
-(0008,0090)
-(0010,1000)
