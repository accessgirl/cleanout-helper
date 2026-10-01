package com.cleanouthelper.organizer

/**
 * Sorts documents into everyday topics ("Bills & Receipts", "Medical & Health", …) by looking
 * for tell-tale words in the title and the first part of the text.
 */
object Topics {
    class Topic(val folder: String, val rules: List<Rule>)

    /** A phrase that suggests a topic. [label] names the kind of document, e.g. "Receipt". */
    class Rule(val phrase: String, val weight: Int, val label: String?) {
        val regex = Regex(
            "(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(phrase).replace(" ", "\\E\\s+\\Q") +
                (if (phrase.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}])" else ""),
        )
    }

    private fun topic(folder: String, vararg rules: Triple<String, Int, String?>) =
        Topic(folder, rules.map { Rule(it.first, it.second, it.third) })

    private fun r(phrase: String, weight: Int, label: String? = null) = Triple(phrase, weight, label)

    val all = listOf(
        topic(
            "Bills & Receipts",
            r("receipt", 3, "Receipt"), r("invoice", 3, "Invoice"), r("amount due", 3, "Bill"), r("bill", 1, "Bill"),
            r("order confirmation", 3, "Order confirmation"), r("order number", 2, "Order"), r("order #", 2, "Order"),
            r("subtotal", 2, "Receipt"), r("total due", 3, "Bill"), r("payment due", 3, "Bill"), r("thank you for your order", 3, "Order confirmation"),
            r("thank you for your purchase", 3, "Receipt"), r("sales tax", 1), r("utility", 1, "Bill"), r("electricity", 2, "Bill"),
            r("water bill", 3, "Bill"), r("gas bill", 3, "Bill"), r("phone bill", 3, "Bill"), r("billing period", 3, "Bill"),
            r("account summary", 1), r("paid", 1), r("refund", 2, "Refund"), r("delivery", 1), r("shipped", 1, "Order"),
        ),
        topic(
            "Bank & Money",
            r("bank statement", 4, "Bank statement"), r("account statement", 4, "Statement"), r("statement period", 3, "Statement"),
            r("opening balance", 3, "Statement"), r("closing balance", 3, "Statement"), r("available balance", 2), r("sort code", 2),
            r("routing number", 2), r("iban", 2), r("credit card statement", 4, "Credit card statement"), r("minimum payment", 2, "Credit card statement"),
            r("loan", 2, "Loan"), r("mortgage", 3, "Mortgage"), r("pay stub", 4, "Pay stub"), r("payslip", 4, "Payslip"), r("pay slip", 4, "Payslip"),
            r("earnings statement", 4, "Pay stub"), r("net pay", 3, "Pay stub"), r("gross pay", 3, "Pay stub"), r("pension", 2), r("401k", 2),
            r("investment", 2), r("dividend", 2), r("bank", 1), r("deposit", 1), r("withdrawal", 2), r("transfer", 1),
        ),
        topic(
            "Taxes",
            r("tax return", 4, "Tax return"), r("form 1040", 4, "Tax return"), r("1040", 2, "Tax return"), r("w-2", 4, "W-2"), r("w2", 2, "W-2"),
            r("1099", 3, "1099"), r("irs", 3, "Tax document"), r("hmrc", 3, "Tax document"), r("self assessment", 3, "Tax return"),
            r("tax year", 3, "Tax document"), r("p60", 4, "P60"), r("p45", 4, "P45"), r("taxable income", 3, "Tax document"), r("cra", 1),
        ),
        topic(
            "Medical & Health",
            r("patient", 3, "Medical record"), r("prescription", 4, "Prescription"), r("diagnosis", 3, "Medical record"),
            r("lab results", 4, "Lab results"), r("test results", 3, "Test results"), r("hospital", 3, "Medical"), r("clinic", 2, "Medical"),
            r("physician", 3, "Medical"), r("doctor", 2, "Medical"), r("medical", 2, "Medical"), r("pharmacy", 3, "Prescription"),
            r("dosage", 3, "Prescription"), r("vaccination", 4, "Vaccination record"), r("immunization", 4, "Vaccination record"),
            r("blood pressure", 3, "Medical"), r("dental", 3, "Dental"), r("dentist", 3, "Dental"), r("optometrist", 3, "Eye test"),
            r("appointment", 1, "Appointment"), r("nhs", 2, "Medical"), r("medicare", 3, "Medical"), r("medicaid", 3, "Medical"),
        ),
        topic(
            "Insurance",
            r("insurance", 3, "Insurance"), r("policy number", 4, "Insurance policy"), r("policyholder", 4, "Insurance policy"),
            r("premium", 2, "Insurance"), r("claim number", 4, "Insurance claim"), r("claim", 1, "Insurance claim"),
            r("coverage", 2, "Insurance"), r("deductible", 3, "Insurance"), r("insured", 3, "Insurance"),
        ),
        topic(
            "Travel & Tickets",
            r("boarding pass", 5, "Boarding pass"), r("itinerary", 4, "Itinerary"), r("flight", 3, "Flight"), r("booking confirmation", 4, "Booking"),
            r("reservation", 3, "Reservation"), r("hotel", 3, "Hotel booking"), r("check-in", 2), r("check in", 2), r("check-out", 2),
            r("e-ticket", 4, "Ticket"), r("ticket", 3, "Ticket"), r("gate", 1), r("seat", 1), r("departure", 3, "Travel"), r("arrival", 2, "Travel"),
            r("passenger", 3, "Ticket"), r("airline", 3, "Flight"), r("train", 2, "Train ticket"), r("car rental", 4, "Car rental"),
            r("visa", 2, "Visa"), r("admit one", 4, "Ticket"), r("event ticket", 4, "Ticket"),
        ),
        topic(
            "Work & Career",
            r("resume", 4, "Resume"), r("résumé", 4, "Resume"), r("curriculum vitae", 5, "CV"), r("cover letter", 5, "Cover letter"),
            r("offer letter", 5, "Job offer"), r("employment", 3, "Employment"), r("employer", 2), r("employee", 2), r("job description", 4, "Job description"),
            r("work experience", 4, "Resume"), r("references available", 3, "Resume"), r("performance review", 4, "Performance review"),
            r("timesheet", 4, "Timesheet"), r("meeting minutes", 4, "Meeting notes"), r("agenda", 2, "Agenda"),
        ),
        topic(
            "Legal & Contracts",
            r("agreement", 3, "Agreement"), r("contract", 3, "Contract"), r("lease", 3, "Lease"), r("tenancy", 4, "Tenancy agreement"),
            r("landlord", 3, "Lease"), r("tenant", 3, "Lease"), r("terms and conditions", 3, "Terms"), r("power of attorney", 5, "Power of attorney"),
            r("last will", 5, "Will"), r("testament", 3, "Will"), r("deed", 3, "Deed"), r("court", 3, "Court document"), r("hereby", 2),
            r("notary", 4, "Notarized document"), r("signature", 1), r("witness", 2), r("plaintiff", 4, "Court document"), r("defendant", 4, "Court document"),
        ),
        topic(
            "IDs & Certificates",
            r("passport", 4, "Passport"), r("driver's license", 5, "Driver's license"), r("driving licence", 5, "Driving licence"),
            r("birth certificate", 5, "Birth certificate"), r("marriage certificate", 5, "Marriage certificate"), r("death certificate", 5, "Death certificate"),
            r("social security", 4, "Social Security"), r("national insurance number", 4, "National Insurance"), r("identity card", 4, "ID card"),
            r("id card", 4, "ID card"), r("certificate", 2, "Certificate"), r("date of birth", 2), r("certify", 2, "Certificate"),
            r("vehicle registration", 4, "Vehicle registration"), r("v5c", 4, "Vehicle registration"), r("diploma", 4, "Diploma"),
        ),
        topic(
            "School & Learning",
            r("school", 2, "School"), r("student", 2, "School"), r("homework", 4, "Homework"), r("assignment", 3, "Assignment"),
            r("syllabus", 4, "Syllabus"), r("transcript", 3, "Transcript"), r("report card", 5, "Report card"), r("grade", 1), r("course", 2),
            r("lecture", 3, "Lecture notes"), r("university", 2), r("college", 2), r("exam", 3, "Exam"), r("quiz", 3, "Quiz"), r("worksheet", 4, "Worksheet"),
            r("teacher", 2), r("semester", 3), r("term", 1), r("enrollment", 3, "Enrollment"), r("enrolment", 3, "Enrolment"),
        ),
        topic(
            "Manuals & Warranties",
            r("user manual", 5, "Manual"), r("instruction manual", 5, "Manual"), r("owner's manual", 5, "Manual"), r("user guide", 4, "User guide"),
            r("quick start guide", 5, "Quick start guide"), r("installation guide", 5, "Installation guide"), r("installation instructions", 5, "Installation guide"),
            r("warranty", 4, "Warranty"), r("guarantee", 2, "Warranty"), r("troubleshooting", 3, "Manual"), r("safety instructions", 3, "Manual"),
            r("model number", 2), r("serial number", 2),
        ),
        topic(
            "Home & Car",
            r("vehicle", 2), r("mot", 1), r("registration", 1), r("service history", 4, "Car service"), r("mileage", 3, "Car"),
            r("council tax", 4, "Council tax"), r("property tax", 4, "Property tax"), r("hoa", 2), r("home improvement", 3),
            r("floor plan", 4, "Floor plan"), r("estimate", 2, "Estimate"), r("quote", 2, "Quote"), r("quotation", 3, "Quote"), r("repair", 2, "Repair"),
        ),
        topic(
            "Recipes",
            r("ingredients", 4, "Recipe"), r("preheat", 4, "Recipe"), r("tablespoon", 3, "Recipe"), r("teaspoon", 3, "Recipe"), r("tbsp", 3, "Recipe"),
            r("tsp", 3, "Recipe"), r("recipe", 4, "Recipe"), r("servings", 3, "Recipe"), r("bake", 2, "Recipe"), r("oven", 2, "Recipe"),
        ),
    )

    data class Match(val folder: String, val label: String?, val score: Int)

    /** Best topic for a document, or null if nothing stands out. */
    fun classify(title: String?, text: String?): Match? {
        val head = text.orEmpty().take(3000)
        val t = title.orEmpty()
        var best: Match? = null
        for (topic in all) {
            var score = 0
            var rulesMatched = 0
            var bestLabel: String? = null
            var bestLabelWeight = 0
            for (rule in topic.rules) {
                val inTitle = rule.regex.containsMatchIn(t)
                val count = rule.regex.findAll(head).take(3).count()
                if (!inTitle && count == 0) continue
                val points = rule.weight * (if (inTitle) 3 else 0) + rule.weight * count
                score += points
                rulesMatched++
                val labelWeight = rule.weight * (if (inTitle) 10 else 1)
                if (rule.label != null && labelWeight > bestLabelWeight) {
                    bestLabel = rule.label
                    bestLabelWeight = labelWeight
                }
            }
            // One stray word ("passport" in a packing list) isn't enough: it takes two clues, or a title match.
            val convincing = rulesMatched >= 2 && score >= 5 || score >= 12
            if (convincing && (best == null || score > best.score)) best = Match(topic.folder, bestLabel, score)
        }
        return best
    }
}
